import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHmac, randomUUID } from 'node:crypto';

// This runner provisions roles only inside the disposable integration-test container.
// It never reads or edits application datasource configuration or uses a shared database.
assert.equal(process.env.S305_ALLOW_ISOLATED_DB_SETUP, '1', 'Set S305_ALLOW_ISOLATED_DB_SETUP=1 for the disposable test environment');
const container = process.env.S305_MYSQL_CONTAINER || 'glassstore-s305-tests';
assert.match(container, /^glassstore-s305-tests(?:-[a-z0-9-]+)?$/, 'Refusing SQL outside a named S3-05 disposable test container');
const identity = process.env.IDENTITY_URL || 'http://127.0.0.1:18081';
const lab = process.env.LAB_URL || 'http://127.0.0.1:18084';
const order = process.env.ORDER_URL || 'http://127.0.0.1:18083';
for (const url of [identity, lab, order]) {
  const parsed = new URL(url);
  assert.ok(['localhost', '127.0.0.1', '[::1]'].includes(parsed.hostname), 'Runtime verification must target loopback services');
  assert.ok(['18081', '18084', '18083'].includes(parsed.port), 'Runtime verification must use the isolated service ports');
}
const inspectedName = execFileSync('docker', ['inspect', '--format', '{{.Name}}', container], { encoding: 'utf8' }).trim();
assert.equal(inspectedName, '/' + container);
const runId = Date.now().toString(36) + randomUUID().replaceAll('-', '').slice(0, 6);
const password = 'S305-Only-Tests!42';
const checks = [];
const actors = {};

function sql(database, statement) {
  assert.ok(['db_identity', 'db_lab', 'db_order'].includes(database));
  return execFileSync('docker', ['exec', '-e', 'MYSQL_PWD=' + (process.env.S305_MYSQL_PASSWORD || 's305-local-tests'),
    container, 'mysql', '-uroot', '--batch', '--skip-column-names', database, '-e', statement],
  { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
}

async function call(label, base, path, { method = 'GET', token, body, expected = 200 } = {}) {
  const response = await fetch(base + path, {
    method, headers: { ...(token ? { Authorization: 'Bearer ' + token } : {}), ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }), signal: AbortSignal.timeout(20000),
  });
  const text = await response.text();
  let payload;
  try { payload = text ? JSON.parse(text) : null; } catch { payload = null; }
  const valid = Array.isArray(expected) ? expected.includes(response.status) : expected === response.status;
  checks.push({ label, method, path, expected, actual: response.status, result: valid ? 'PASS' : 'FAIL' });
  assert.ok(valid, label + ': expected HTTP ' + JSON.stringify(expected) + ', actual ' + response.status);
  return { status: response.status, payload, data: payload?.data };
}

function measurement(customerId, additions = {}) {
  return { customerId, odSph: -2, odCyl: 0, odAxis: 0, odAdd: 0,
    osSph: -1.5, osCyl: 0, osAxis: 0, osAdd: 0, pd: 63, notes: 'S305 runtime ' + runId, ...additions };
}

function serviceToken() {
  if (process.env.SERVICE_TOKEN) return process.env.SERVICE_TOKEN;
  const secret = process.env.S305_JWT_SECRET;
  assert.ok(secret && Buffer.byteLength(secret) >= 32, 'S305_JWT_SECRET or SERVICE_TOKEN is required for real Lab dispatch verification');
  const now = Math.floor(Date.now() / 1000);
  const encode = value => Buffer.from(JSON.stringify(value)).toString('base64url');
  const unsigned = encode({ alg: 'HS256', typ: 'JWT' }) + '.' + encode({
    sub: 'service:s305-runtime-check', iat: now, exp: now + 300, type: 'SERVICE', roles: ['ROLE_SERVICE'],
  });
  return unsigned + '.' + createHmac('sha256', secret).update(unsigned).digest('base64url');
}

async function register(name, role) {
  const email = `s305_${name}_${runId}@example.test`;
  const response = await call('register ' + name, identity, '/api/v1/auth/register', {
    method: 'POST', expected: 201,
    body: { email, password, fullName: `S305 ${name} ${runId}`, roles: ['ROLE_ADMIN'], roleName: 'ROLE_ADMIN' },
  });
  assert.ok(Number.isSafeInteger(response.data?.id) && response.data.id > 0);
  assert.deepEqual(response.data.roles, ['ROLE_CUSTOMER'], 'Public registration cannot assign privileged roles');
  const id = response.data.id;
  if (role) {
    assert.ok(['ROLE_OPTOMETRIST', 'ROLE_SALES_STAFF'].includes(role));
    sql('db_identity', `INSERT IGNORE INTO roles(name,description) VALUES ('${role}','S305 isolated runtime role');
      DELETE FROM user_roles WHERE user_id=${id};
      INSERT INTO user_roles(user_id,role_id) SELECT ${id},id FROM roles WHERE name='${role}';`);
  }
  const signedIn = await call('login ' + name, identity, '/api/v1/auth/login', {
    method: 'POST', body: { email, password },
  });
  assert.ok(signedIn.data?.token && signedIn.data?.refreshToken, 'Login returns access and refresh tokens');
  actors[name] = { id, email, fullName: response.data.fullName, token: signedIn.data.token, refreshToken: signedIn.data.refreshToken };
  return actors[name];
}

try {
  const customer = await register('customer');
  const outsider = await register('outsider');
  const clinician = await register('clinician', 'ROLE_OPTOMETRIST');
  const secondClinician = await register('second', 'ROLE_OPTOMETRIST');
  const sales = await register('sales', 'ROLE_SALES_STAFF');
  await call('duplicate email', identity, '/api/v1/auth/register', { method: 'POST', expected: 409,
    body: { email: customer.email, fullName: 'Duplicate', password } });
  await call('wrong password', identity, '/api/v1/auth/login', { method: 'POST', expected: 401,
    body: { email: customer.email, password: 'wrong-password' } });
  const me = await call('authenticated profile', identity, '/api/v1/auth/me', { token: customer.token });
  assert.equal(me.data.id, customer.id);
  const refreshed = await call('refresh token', identity, '/api/v1/auth/refresh', { method: 'POST', body: { refreshToken: customer.refreshToken } });
  assert.ok(refreshed.data.token);
  await call('refresh token cannot act as access token', lab, '/api/prescriptions/customer/' + customer.id,
    { token: customer.refreshToken, expected: 401 });

  const input = measurement(customer.id);
  await call('unauthenticated prescription create', lab, '/api/prescriptions', { method: 'POST', body: input, expected: 401 });
  for (const name of ['customer', 'sales']) {
    await call(name + ' cannot prescribe', lab, '/api/prescriptions', { method: 'POST', token: actors[name].token, body: input, expected: 403 });
  }
  const beforeInvalid = Number(sql('db_lab', `SELECT COUNT(*) FROM prescriptions WHERE customer_id=${customer.id}`));
  for (const field of ['odSph', 'odCyl', 'odAxis', 'odAdd', 'osSph', 'osCyl', 'osAxis', 'osAdd', 'pd']) {
    const missing = { ...input }; delete missing[field];
    await call('missing ' + field, lab, '/api/prescriptions', { method: 'POST', token: clinician.token, body: missing, expected: 400 });
    await call('null ' + field, lab, '/api/prescriptions', { method: 'POST', token: clinician.token, body: { ...input, [field]: null }, expected: 400 });
  }
  for (const [field, value] of [['odSph', 30.01], ['osSph', -30.01], ['odCyl', 10.01], ['osCyl', -10.01],
    ['odAxis', 181], ['osAxis', -1], ['odAxis', 12.5], ['odAdd', 4.01], ['osAdd', -0.01], ['pd', 39.9], ['pd', 90.1], ['pd', 63.01]]) {
    await call('invalid ' + field + '=' + value, lab, '/api/prescriptions', {
      method: 'POST', token: clinician.token, body: { ...input, [field]: value }, expected: 400,
    });
  }
  await call('unknown customer rejected through real identity lookup', lab, '/api/prescriptions', {
    method: 'POST', token: clinician.token, body: { ...input, customerId: 2147483647 }, expected: 400,
  });
  assert.equal(Number(sql('db_lab', `SELECT COUNT(*) FROM prescriptions WHERE customer_id=${customer.id}`)), beforeInvalid);
  const created = await call('create prescription with explicit zero and spoofed actor', lab, '/api/prescriptions', {
    method: 'POST', token: clinician.token, body: { ...input, optometristId: outsider.id, optometristName: 'Forged', customerName: 'Forged' },
  });
  const rxId = created.data.id;
  assert.equal(created.data.version, 0);
  assert.equal(created.data.optometristId, clinician.id);
  assert.equal(created.data.customerName, customer.fullName);
  assert.equal(created.data.odCyl, 0);
  await call('other customer cannot read prescription', lab, '/api/prescriptions/' + rxId, { token: outsider.token, expected: 403 });
  await call('other customer cannot read history', lab, `/api/prescriptions/${rxId}/revisions`, { token: outsider.token, expected: 403 });

  const booking = await call('customer booking uses authenticated owner', lab, '/api/appointments', {
    method: 'POST', token: customer.token, body: { customerId: outsider.id, customerName: 'Forged', status: 'COMPLETED',
      scheduledTime: new Date(Date.now() + 86400000).toISOString(), notes: 'S305 booking ' + runId },
  });
  const appointmentId = booking.data.id;
  assert.equal(booking.data.customerId, customer.id);
  assert.equal(booking.data.status, 'WAITING');
  await call('sales cannot start exam', lab, `/api/appointments/${appointmentId}/status`, {
    method: 'PATCH', token: sales.token, body: { status: 'IN_EXAM', expectedVersion: 0, roomName: 'S305 exam room' }, expected: 403,
  });
  const started = await call('clinician starts exam', lab, `/api/appointments/${appointmentId}/status`, {
    method: 'PATCH', token: clinician.token, body: { status: 'IN_EXAM', expectedVersion: 0, roomName: 'S305 exam room' },
  });
  assert.equal(started.data.optometristId, clinician.id);
  await call('completion requires saved prescription', lab, `/api/appointments/${appointmentId}/status`, {
    method: 'PATCH', token: clinician.token, body: { status: 'COMPLETED', expectedVersion: started.data.version }, expected: 400,
  });
  const completed = await call('complete exam with saved prescription', lab, `/api/appointments/${appointmentId}/status`, {
    method: 'PATCH', token: clinician.token,
    body: { status: 'COMPLETED', expectedVersion: started.data.version, prescriptionId: rxId },
  });
  assert.equal(completed.data.status, 'COMPLETED');
  assert.equal(completed.data.prescriptionId, rxId);
  const bookingRead = await call('booking readback', lab, '/api/appointments/' + appointmentId, { token: customer.token });
  assert.equal(bookingRead.data.status, 'COMPLETED');
  await call('outsider booking read rejected', lab, '/api/appointments/' + appointmentId, { token: outsider.token, expected: 403 });

  const dispatched = await call('real service dispatch pins revision zero', lab, '/api/lab-jobs/from-order', {
    method: 'POST', token: serviceToken(), expected: 201,
    body: { orderId: 900000000 + rxId, customerId: customer.id, prescriptionId: rxId, prescriptionVersion: 0,
      frameSku: 'S305-FRAME', lensSku: 'S305-LENS', idempotencyKey: 'S305-' + runId },
  });
  const jobId = dispatched.data.id;
  assert.equal(dispatched.data.prescriptionVersion, 0);
  assert.equal(dispatched.data.odSph, -2);
  const corrected = await call('controlled correction', lab, '/api/prescriptions/' + rxId, {
    method: 'PATCH', token: secondClinician.token,
    body: measurement(customer.id, { expectedVersion: 0, reason: 'Repeat measurement ' + runId, odSph: -3.25, actorId: outsider.id }),
  });
  assert.equal(corrected.data.version, 1);
  assert.equal(corrected.data.optometristId, clinician.id);
  const history = await call('immutable before after and editor', lab, `/api/prescriptions/${rxId}/revisions`, { token: customer.token });
  assert.deepEqual(history.data.map(entry => entry.version), [1, 0]);
  assert.equal(history.data[0].actorId, secondClinician.id);
  assert.equal(history.data[0].odSph, -3.25);
  assert.equal(history.data[1].odSph, -2);
  const original = await call('immutable revision readback', lab, `/api/prescriptions/${rxId}/revisions/0`, { token: customer.token });
  assert.equal(original.data.odSph, -2);
  await call('stale edit conflict', lab, '/api/prescriptions/' + rxId, {
    method: 'PATCH', token: clinician.token, body: measurement(customer.id, { expectedVersion: 0, reason: 'Stale edit', odSph: -8 }), expected: 409,
  });
  await call('reason required', lab, '/api/prescriptions/' + rxId, {
    method: 'PATCH', token: clinician.token, body: measurement(customer.id, { expectedVersion: 1, reason: ' ', odSph: -8 }), expected: 400,
  });
  const concurrent = await Promise.all([
    call('concurrent writer one', lab, '/api/prescriptions/' + rxId, { method: 'PATCH', token: clinician.token,
      body: measurement(customer.id, { expectedVersion: 1, reason: 'Concurrent one', odSph: -4 }), expected: [200, 409] }),
    call('concurrent writer two', lab, '/api/prescriptions/' + rxId, { method: 'PATCH', token: secondClinician.token,
      body: measurement(customer.id, { expectedVersion: 1, reason: 'Concurrent two', odSph: -5 }), expected: [200, 409] }),
  ]);
  assert.deepEqual(concurrent.map(result => result.status).sort(), [200, 409]);
  const final = await call('final prescription readback', lab, '/api/prescriptions/' + rxId, { token: customer.token });
  assert.equal(final.data.version, 2);
  assert.equal(Number(sql('db_lab', `SELECT COUNT(*) FROM prescription_revisions WHERE prescription_id=${rxId}`)), 3);
  const pinnedJob = await call('lab job keeps original measurements after corrections', lab, '/api/lab-jobs/' + jobId, { token: sales.token });
  assert.equal(pinnedJob.data.prescriptionVersion, 0);
  assert.equal(pinnedJob.data.odSph, -2);
  assert.equal(Number(sql('db_lab', `SELECT rx_version FROM lab_jobs WHERE id=${jobId}`)), 0);
  assert.equal(Number(sql('db_lab', `SELECT rx_od_sph FROM lab_jobs WHERE id=${jobId}`)), -2);

  console.log(JSON.stringify({ overall: 'PASS', checks, assertions: 'HTTP responses and real MySQL readback',
    fixtureIds: { customerId: customer.id, clinicianId: clinician.id, prescriptionId: rxId, appointmentId, jobId },
    boundaries: ['identity HTTP/JWT', 'lab HTTP', 'MySQL identity/lab', 'service JWT dispatch'],
    cleanup: 'Test-only records remain in the disposable named container; runner must remove it after browser checks.' }, null, 2));
} catch (error) {
  console.log(JSON.stringify({ overall: 'FAIL', checks, error: error.message,
    cleanup: 'Remove the disposable named container after diagnostics.' }, null, 2));
  process.exitCode = 1;
}
