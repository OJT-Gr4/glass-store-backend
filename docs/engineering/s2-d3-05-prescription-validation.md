# S2-D3-05: Prescription validation

Traceability: FR-EYE-04/05/06/08; BR-21/22.

POST /api/prescriptions and /api/v1/prescriptions accept CreatePrescriptionRequest, not a persistence entity. Customer ID and both eyes' SPH, CYL, AXIS, ADD and total PD are required. Explicit zero is accepted for SPH/CYL/AXIS/ADD; null/missing is rejected. No fabricated measurement or actor is inserted. Only ADMIN and OPTOMETRIST may save (existing security policy).

Measurement contract: SPH [-30,30], CYL [-10,10], AXIS integer [0,180], ADD [0,4], total PD [40,90] mm. SPH/CYL/ADD have at most two decimals; PD one decimal. Notes max 500 characters. GET /api/prescriptions/validation-rules derives the client contract directly from DTO annotations; the portal checks it before POST.

Identity contract: lab calls GET {services.identity-service.url}/api/v1/users/{customerId}, forwarding the authenticated bearer token. Default URL http://localhost:8081. Expected envelope data: id, fullName, roles containing CUSTOMER or ROLE_CUSTOMER. Missing/non-customer returns 400; dependency failure returns 503, with no save. The customer name comes from identity; optometrist ID and name come from validated security context. Request actor IDs/names are ignored.

Business confirmation pending: ranges SPH/CYL/AXIS/PD follow the existing fabrication implementation; ADD 0..4 is a provisional implementation choice. Owner must confirm ranges and warnings before marking subtask 5.3 Done. Existing frontend progression warning (>0.75 D within six calendar months) is unchanged and has not been confirmed as a shared backend rule. No business-owner approval or inter-service-owner agreement is implied by this implementation.

Verification: MockMvc integration tests cover persistence, missing/out-of-range requests, role rejection and actor-ID spoofing with JWT security context. Live identity-service integration remains to be verified in the integrated environment.
