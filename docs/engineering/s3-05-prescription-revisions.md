# S3-05: Controlled prescription revisions

Traceability: FR-EYE-04/05/06/08/12. Scope includes AC1 (validation), AC2 (before/after and editor), AC3 (unchanged dispatched lab measurements).

## API contract

Both `/api/prescriptions` and `/api/v1/prescriptions` support the following routes. Responses retain the existing `ApiResponse` envelope.

| Method | Route | Behavior |
| --- | --- | --- |
| POST | `/` | Validate all measurements, verify the customer with identity, take the prescriber from authenticated context, save current row and immutable revision `0` atomically. |
| GET | `/{id}` | Read current prescription including optimistic `version`. Customer access is limited to their own record. Missing ID is 404. |
| PATCH / PUT | `/{id}` | Controlled full replacement with the original `customerId`, all nine measurements, optional notes/lens metadata, `expectedVersion`, and nonblank `reason` (max 500). The response contains the incremented `version`. |
| GET | `/{id}/revisions` | Immutable snapshots, newest version first. Each entry includes all original prescription fields plus `prescriptionId`, `version`, `actorId`, `actorName`, `reason`, and `recordedAt`. |
| GET | `/{id}/revisions/{version}` | One immutable revision for order/Lab consumers; 404 for an unavailable revision. |

`id` on a revision is an alias of `prescriptionId`, not the internal revision table primary key. Original `optometristId` and `optometristName` retain the prescriber; the revision's `actorId` and `actorName` identify the editor. Client-supplied actor and prescriber fields are ignored. Only ADMIN and OPTOMETRIST can create or edit.

The first snapshot is version 0. `expectedVersion` is mandatory even when it is 0. A stale version or a concurrent winning update returns HTTP 409. Request IDs, versions and AXIS values cannot be fractional or silently truncated. Every update requires all measurements; missing/null differs from an explicit numeric zero. A timestamp ensures a valid repeated measurement can still create an audited revision even if the clinical numbers happen to match the previous values.

Current-row updates, old/new snapshot inserts and freezing legacy Lab jobs share one transaction. A losing writer rolls the transaction back. Revision entities have no setters or mutation endpoint and are mapped `@Immutable`.

## Measurement policy

The existing DTO constraints remain unchanged: SPH -30..30, CYL -10..10, integer AXIS 0..180, ADD 0..4, total PD 40..90 mm; SPH/CYL/ADD at most two decimals and PD at most one. All nine values are required, including explicit zero when clinically measured as zero. Missing values are no longer given entity defaults.

These are existing implementation bounds, **not newly approved clinical guidance**. The previous Sprint 2 document says owner confirmation remains pending, particularly ADD. No progression rule or medical algorithm is added. Clinical approval must be recorded before treating this as full business sign-off.

## Existing installations

Review and apply `docker/mysql/migrations/20261108-prescription-revisions.sql` against the intended lab schema **before deploying code**, then apply the separate lab snapshot migration. No database connection configuration is changed and no shared database migration is executed by this task. The migration adds the optimistic version and timestamp columns, creates the append-only snapshot table, and copies the actual pre-deployment values without replacing unknown measurements with zero or PD 63.

Legacy current records without a stored snapshot can be read as their existing version. On their first controlled edit, the real previous row is appended to history before that version becomes historical. Existing Lab jobs without embedded snapshots are frozen from the previous prescription inside that same update transaction. Original attribution is used for the legacy snapshot; no historical editor is invented.

## Validation evidence

New executable Spring/MockMvc/H2 tests:

- `PrescriptionRevisionApiTest`: explicit zero versus missing/null, bounds and precision, fractional AXIS/IDs/version, mandatory reason/version, immutable customer, absent customer and unavailable identity, unauthorized roles, ownership, canonical actor, saved before/after and stale writer.
- `PrescriptionConcurrentUpdateTest`: two separate simultaneous HTTP requests are synchronized after both have loaded version 0; exactly one commits with 200 and the other receives 409; exactly two revision rows (0 and 1) remain.
- `PrescriptionLabRevisionIsolationTest`: legacy Lab jobs retain the pre-edit measurements; a new dispatch can explicitly pin an earlier revision even after the current record changes; subsequent edits leave the job unchanged.

Run from the backend root (PowerShell):

```powershell
mvn -o -pl eyewear-lab-service -am test '-Dtest=PrescriptionRevisionApiTest,PrescriptionConcurrentUpdateTest,PrescriptionLabRevisionIsolationTest,PrescriptionPersistenceTest' '-Dsurefire.failIfNoSpecifiedTests=false'
```

H2 is isolated test infrastructure configured in test annotations only. The identity HTTP dependency is mocked in these tests; they do not establish live identity connectivity, shared MySQL compatibility or a real browser workflow. Actual counts and exit codes are recorded in the overall implementation evidence after execution, and unexecuted tiers must remain unverified.
