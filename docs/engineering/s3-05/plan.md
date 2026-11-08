# S3-05 implementation and acceptance plan

Scope: complete the listed appointment, digital prescription, registration/login, controlled revision, OMS status and A5-label flows across all three repositories. The supplied acceptance criteria are the feature specification; the identifiers below provide local traceability. Existing database connection files are excluded from changes.

| Requirement | Task | Implementation / acceptance |
| --- | --- | --- |
| REQ-001 / AC1 | T001 | Validated prescription DTO: explicit zero preserved, missing values rejected, strict integer AXIS, existing measurement domains retained. Customer verified through identity, examiner from JWT, role-separated writes. |
| REQ-002 / AC2 | T002 | Full controlled PATCH/PUT with expectedVersion and nonblank reason; immutable revisions include actor and time; concurrent edits return 409. Authorized history and revision reads. |
| REQ-003 / AC3 | T003 | Pin prescription version and measurements in order/Lab snapshots; preserve legacy Lab snapshots before edits; live prescription changes cannot rewrite a finalized job. |
| REQ-004 | T004 | Real authenticated appointment booking, queue, clinician assignment, legal status transitions, version checks and completion linked to a saved prescription. |
| REQ-005 | T005 | Real customer digital records/history, clinician edit/history UI, API error handling, no fabricated measurements or success. |
| REQ-006 | T006 | Review registration/login, validate storage bounds, unique identity, BCrypt passwords and JWT ownership; real frontend session validation. |
| REQ-007 | T007 | Real OMS PATCH transitions, authoritative API responses, shipping data and printable A5 label without fabricated tracking or payment. |
| REQ-008 | T008 | Runnable regression tests and expected/actual evidence; report provider or environment limitations honestly. |

Architecture: Spring Boot controllers/DTOs/services/repositories; React/Vite frontends. Current prescription is the mutable aggregate with optimistic version; revisions and embedded order/Lab snapshots retain the measurements used at the time of the action. Original prescriber and revision editor are distinct. Customer identity and state transitions are server controlled.

Database changes are additive SQL migration artifacts for review. Do not run them on the team's database as part of validation. No application connection files, secrets, ports or shared environments are rewritten.

Testing strategy: meaningful node API/helper regressions and both FE production builds; Spring MockMvc/persistence tests on isolated in-memory databases; a dedicated disposable MySQL 8 container for runtime cross-service API verification; Playwright for rendered React flows. Browser fixture tests and real service tests are reported separately. Critical journeys: register/login/me; book/start/complete with ownership; prescription create/edit/history/concurrent conflict; pinned order/Lab isolation; OMS PATCH failure and A5 print. Missing providers or unavailable tiers are documented, never marked passed.

Medical domain: retain existing DTO domains. Existing documentation explicitly records professional confirmation pending; this work does not introduce new medical thresholds or diagnostic algorithms. User clarified to focus on functionality rather than tracking notation.
