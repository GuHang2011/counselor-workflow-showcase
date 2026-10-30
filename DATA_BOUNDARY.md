# Public data boundary

This repository excludes real student records, real names/contact details/identifiers, production databases and attachments, logs, caches, credentials, certificates and private deployment configuration.

The screenshots are captured from the actual local application. Login screens have empty inputs. Workflow screens use generated test accounts (`erp_test_*`) and synthetic tasks; those strings are fixtures, not institutional users. The 2026-10-28 set contains mobile login, desktop/mobile dashboard and mobile search views. General interface branding is retained.

`examples/vue-prelaunch/` contains reviewed source extracts, an integration patch and test source. References to session storage, authorization headers and fixture paths describe implementation; no live token, password or fixture file is included. The browser test source requires an isolated instance of the full application and must not be pointed at a production account.

`docs/evidence/prelaunch-20261028.json` is an allowlisted summary of pass counts and build/report hashes. Raw traces, local filesystem paths, request payloads and test-account credentials are excluded. A successful isolated run is not evidence of production deployment, antivirus integration, security clearance or measured user impact.

## v1.1.0

The six new screenshots use synthetic Demo users and tasks. AI settings show a placeholder endpoint and no key. `examples/app-v1.1.0` adds reviewed frontend/backend source, regression tests and deployment examples; it is not a complete runnable system. Test strings are synthetic fixtures. Only the allowlisted counts and artifact hashes in `docs/evidence/v1.1.0.json` are published; raw reports, runtime databases, credentials and deployment binaries remain local.
