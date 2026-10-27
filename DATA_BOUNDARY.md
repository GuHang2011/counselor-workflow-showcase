# Public data boundary

This repository excludes real student records, real names/contact details/identifiers, production databases and attachments, logs, caches, credentials, certificates and private deployment configuration.

The screenshots are captured from the actual local application. Login screens have empty inputs. Workflow screens use generated test accounts (`erp_test_*`) and synthetic tasks; those strings are fixtures, not institutional users. The 2026-10-28 set contains mobile login, desktop/mobile dashboard and mobile search views. General interface branding is retained.

`examples/vue-prelaunch/` contains reviewed source extracts, an integration patch and test source. References to session storage, authorization headers and fixture paths describe implementation; no live token, password or fixture file is included. The browser test source requires an isolated instance of the full application and must not be pointed at a production account.

`docs/evidence/prelaunch-20261028.json` is an allowlisted summary of pass counts and build/report hashes. Raw traces, local filesystem paths, request payloads and test-account credentials are excluded. A successful isolated run is not evidence of production deployment, antivirus integration, security clearance or measured user impact.
