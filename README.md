# GlassStore Backend

GlassStore uses Java 21, Spring Boot 3.5.16, Spring Cloud 2025.0.3 and MySQL 8.0. The standard local environment has one MySQL server, four schemas, five backend processes and two frontend development servers.

## 1. Standard ports and databases

| Component | Address or port | Schema |
|---|---|---|
| MySQL | 127.0.0.1:3306 | Four schemas below |
| API Gateway | 127.0.0.1:8080 | None |
| Identity | 127.0.0.1:8081 | db_identity |
| Catalog | 127.0.0.1:8082 | db_catalog |
| Order/POS | 127.0.0.1:8083 | db_order |
| Eyewear Lab | 127.0.0.1:8084 | db_lab |
| Customer web | http://127.0.0.1:5173 | Through gateway |
| Internal portal | http://127.0.0.1:5174/login | Through gateway |

Use 127.0.0.1 consistently when logging in; localhost and 127.0.0.1 have separate browser storage. Frontend requests use relative /api URLs through the Vite proxy. Do not connect the browser directly to a service or expose a JWT signing secret in frontend variables.

## 2. Prerequisites and folder layout

- JDK 21. Set JAVA_HOME to the JDK directory and add its bin directory to PATH. JDK 25 is not the project baseline.
- Maven 3.9 or newer. Add mvn.cmd to PATH, or set MVN_CMD to its full path in backend/.env.
- Node.js 20 or newer and npm. Commit and use the existing package-lock.json files; install with npm ci.
- MySQL 8.0 and mysql.exe for native mode, or a running Docker Desktop installation for Docker mode.
- Windows PowerShell 5.1 or newer for the supplied .bat/.ps1 launchers.

Place the three repositories as follows. The parent directory can have any name:

```text
GlassStore/
  backend/
  frontend/
    customer-web/
    internal-portal/
```

If your checkout names differ, set CUSTOMER_WEB_DIR and INTERNAL_PORTAL_DIR to their absolute paths in backend/.env. The launcher resolves its backend directory from the script location, not a developer-specific C:\Users path.

For example, to clone a new workspace:

```powershell
mkdir GlassStore
cd GlassStore
git clone -b dev https://github.com/OJT-Gr4/glass-store-backend.git backend
mkdir frontend
git clone -b dev https://github.com/OJT-Gr4/glass-store-customer-web-.git frontend/customer-web
git clone -b dev https://github.com/OJT-Gr4/glass-store-internal-portal.git frontend/internal-portal
```

## 3. One configuration file for local startup

In backend, copy .env.example to .env and review the values:

```powershell
Copy-Item .env.example .env
```

.env is private to the machine and ignored by Git. Use unquoted KEY=value lines. Application configuration imports this file; the shared launcher loads it for all processes. Do not commit credentials, runtime logs or database backups.

| Setting | Default | Meaning |
|---|---|---|
| DB_MODE | native | native or docker for setup/check tools |
| DB_HOST | 127.0.0.1 | MySQL hostname |
| DB_PORT | 3306 | MySQL port for all four services |
| DB_USERNAME | root | Local database user |
| DB_PASSWORD | root | Development example; replace for your server |
| JWT_SECRET | Development value in .env.example | Same signing key for all five services |
| IDENTITY_PORT / CATALOG_PORT / ORDER_PORT / LAB_PORT | 8081 / 8082 / 8083 / 8084 | Backend listeners and default internal routes |
| GATEWAY_PORT | 8080 | Gateway listener and frontend proxy target |
| CUSTOMER_WEB_PORT / INTERNAL_PORTAL_PORT | 5173 / 5174 | Frontend listeners |
| DEMO_SEED_ENABLED | false | Legacy Java sample initializers disabled; canonical SQL is the source for new machines |
| SHOW_SQL | false | SQL logging |
| JPA_DDL_AUTO | update | Local schema update; not a substitute for reviewed migrations |

Keep the standard ports unless the team deliberately agrees otherwise. Changing a port requires consistent URLs/proxy settings. Vite uses strictPort and fails when its port is occupied instead of silently switching to 5175 or another port. If starting a service through the IDE, use backend or its module as the working directory so the shared .env can be located.

An old frontend .env.local may override API settings. The shared launcher rejects old non-empty direct API URLs; leave VITE_API_BASE_URL or VITE_API_GATEWAY_URL empty to use the proxy.

## 4. Database setup: choose one mode

The setup tools never drop a database, reset a volume or import the seed over existing tables. They do not silently switch between the old Docker 3307 instance and native MySQL 3306.

### 4.1 Native MySQL, including the existing team demo machine

Set DB_MODE=native and the credentials for the actual MySQL server. Ensure its Windows service is running. If mysql.exe is not on PATH, set MYSQL_EXE to its full path in .env.

On a new server with no GlassStore tables:

```powershell
.\SETUP_DATABASE.bat -Mode native
```

On a server that already has GlassStore data:

```powershell
.\SETUP_DATABASE.bat -Mode native -CheckOnly
```

The first command refuses a database that already contains GlassStore tables. For an upgrade, keep the existing data and review the SQL under docker/mysql/migrations individually. Do not rerun init scripts or enable legacy Java seed as an upgrade method.

### 4.2 Docker MySQL on a new machine

Set DB_MODE=docker, DB_HOST=127.0.0.1, DB_PORT=3306 and DB_USERNAME=root in .env. Start Docker Desktop, then run:

```powershell
.\SETUP_DATABASE.bat -Mode docker
```

The setup uses docker-compose.yml, container glassstore-dev-mysql and volume glassstore_dev_mysql_data_v2. It waits for MySQL health. If native MySQL already occupies 3306, setup stops with an error; it does not stop that server or choose a different data source automatically. Use native mode for that server instead.

The new volume is separate from the historical glass_store_mysql_data volume on 3307. No old volume is deleted or migrated automatically. Docker init scripts execute only for a new empty volume. Stopping the applications does not stop MySQL.

Docker database smoke test on 2026-11-06 passed: MySQL 8.0 became healthy, all three init scripts loaded the baseline counts below, a Windows MySQL client connected through the published TCP port, and records survived a container restart. The test used a separate container/volume and temporary port 13316 because native MySQL occupied 3306. This checks database bootstrap and persistence; it does not claim a full application UAT against Docker. Standard team configuration remains port 3306.

## 5. Canonical development dataset

The new-machine setup runs these scripts in order:

1. docker/mysql/init/01-init-databases.sql creates the four schemas.
2. docker/mysql/init/02-seed-data.sql creates baseline tables and consistent sample records. A guard rejects existing tables before loading data.
3. docker/mysql/init/03-opening-inventory.sql creates opening balances for LAB, BRANCH_01 and BRANCH_02 without increasing total stock.

Verified baseline:

| Data | Count |
|---|---:|
| Users | 14 |
| Products | 15 |
| Product variants/SKUs | 21 |
| Orders | 6 |
| Prescriptions | 6 |
| Lab jobs | 5 |
| Inventory balances | 63 |
| Total stock across locations | 1540 |

Opening stock: BRANCH_01 has 105 units, BRANCH_02 has 105 units and LAB has 1330 units. Each SKU receives up to 5 units per branch, respecting its total quantity. Catalog startup creates only missing structural locations/balances and preserves existing balances; a restart does not refill sold stock.

Sample users for this SQL dataset all use password 123456, stored as a verified BCrypt hash:

| Login | Email | Role |
|---|---|---|
| admin | adminoptic@gmail.com | ROLE_ADMIN |
| staff | posoptic@gmail.com | ROLE_SALES_STAFF |
| optometrist | optometryoptic@gmail.com | ROLE_OPTOMETRIST |
| labtech | laboptic@gmail.com | ROLE_LAB_TECH |
| customer | customer@gmail.com | ROLE_CUSTOMER |

The sample records are development fixtures, not proof of successful payment-provider or hardware integration. Runtime data can differ after sales and transfers. Matching initial seed data does not synchronize later changes between developers' local databases.

Historical datasets may contain an incorrect default-password hash. Check and repair only that known fixture with:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/repair-demo-credentials.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/repair-demo-credentials.ps1 -Apply
```

The repair requires the exact old hash and canonical username/email pair. It does not reset custom passwords, create accounts or change roles. Before applying, it stores a private backup under .runtime/backups. Backend login now checks only BCrypt; there is no universal demo-password bypass.

## 6. Run all five BE services and both FE sites

From backend:

```powershell
.\START_GLASSSTORE.bat
```

The launcher checks JDK 21, Node/npm, the database, frontend directories and all seven application ports. It builds the backend with Maven, runs npm ci if node_modules is missing, then launches processes in the background with hidden windows. It records process IDs and logs in backend/.runtime.

It never starts a second instance on occupied ports. A launch acknowledgement is not proof that all services finished startup. Check readiness with:

```powershell
.\CHECK_GLASSSTORE.bat
```

After a successful build/install, an optional quicker launch is:

```powershell
.\START_GLASSSTORE.bat -SkipBuild -SkipInstall
```

Only use -SkipBuild when existing JARs match the current source. After pulling Java changes or configuration changes, rebuild and restart the applications. Do not use old JARs to demonstrate new code.

To stop applications created by this launcher:

```powershell
.\STOP_GLASSSTORE.bat
```

Stop checks PID/start-time records and stops only its own process trees, not every application using the same port. It leaves MySQL and its data running. Processes launched manually or by an older script are not automatically taken over; close their original sessions first.

The parent-folder start_all.bat/start_all.ps1/run_project.bat wrappers delegate to this launcher when present. The backend .bat files and scripts are tracked in the backend repository and are the portable entry points for teammates.

## 7. Troubleshooting

| Symptom | Check |
|---|---|
| Port already occupied | Run CHECK_GLASSSTORE.bat. Do not launch twice or kill an unrelated process. |
| Java 25 / unsupported release | Correct JAVA_HOME/PATH to JDK 21 and verify java -version and mvn -v. |
| DB connection refused | Verify DB_MODE, DB_HOST/DB_PORT and MySQL service/Docker health. |
| DB access denied | Review DB_USERNAME/DB_PASSWORD in private .env; do not change application code per machine. |
| Missing tables/data | On a fresh machine run setup once. On an existing DB review migrations; do not reset it. |
| POS reports out of stock | Inspect quantities at the selected branch, not just global/LAB stock. Use a real transfer for an existing DB. |
| Login 401 | Use actual usernames above; verify password hash/status and clear an expired browser session. Do not reintroduce a fake token or password bypass. |
| Service-to-service 401 | Use the same JWT_SECRET across the five processes and restart them after changes. |
| Browser 404/502 | Use the documented FE URLs and inspect gateway/downstream logs. |
| Vite silently uses another port | Updated configs use strictPort. Check that the teammate pulled the frontend changes too. |

Logs are .runtime/<service>.log and .runtime/<service>.err.log. CHECK_GLASSSTORE checks both FE HTTP responses, public catalog and the expected unauthenticated 401 for orders. It does not automatically relaunch services or report UAT completion.

The first catalog request can take longer during startup. HTTP readiness checks wait up to 30 seconds per request and still fail on a real timeout/error; this startup check is not a performance certification.

## 8. Validation and data sharing

Compile/package with Java 21 and run the tests relevant to changes. Spring integration tests must use their H2 test configuration or explicit test datasource overrides; never point create-drop tests at the development MySQL server.

Share code, .env.example, canonical init scripts and reviewed migrations through the repositories. Personal .env, logs, credential backups and runtime data remain local. If the team needs one live shared dataset, provision an agreed database server and access policy separately; do not open a developer's MySQL to the internet as part of this local setup.

For S3-13 stock vouchers, supplier administration, and per-location defect quarantine, see [the engineering notes](docs/engineering/s3-13-stock-vouchers.md) and review the additive migration before deploying.
