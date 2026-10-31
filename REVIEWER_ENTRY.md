# Reviewer entry

## Problem

Administrative requests often move through several roles and states. The case study shows how to make those transitions explicit and readable.

## Contribution

This public selection focuses on the interaction model, state transitions, API contract, architecture explanation, and responsive presentation. It is not a copy of a production system.

## v1.1.3 review

Start with the [eight UX fixes](docs/ux-review-20261031.md), [source and tests](examples/app-v1.1.3/README.md), [screenshots](docs/screenshots-v1.1.3.md) and [acceptance evidence](docs/evidence/v1.1.3.json).

## v1.1.2 review

Start with the [seven functional fixes](docs/functional-review-20261031.md), [source and regression tests](examples/app-v1.1.2/README.md), [screenshots](docs/screenshots-v1.1.2.md) and [validation summary](docs/evidence/v1.1.2.json).

## Previous implementation review

Start with [v1.1.0](docs/releases/v1.1.0.md), [security findings](docs/security-review-20261030.md), [current extracts](examples/app-v1.1.0/README.md) and [screenshots](docs/screenshots-v1.1.0.md).

### Previous review

Start with the [2026-10-28 problem/fix table](docs/prelaunch-review-20261028.md), then inspect the [Vue extracts and integration patch](examples/vue-prelaunch/README.md). The 32-case browser result belongs to the full isolated application; the public static demo has its own four contract checks.

## Evidence path

1. Open the [live demo](https://guhang2011.github.io/counselor-workflow-showcase/).
2. Read [`src/workflow-state.ts`](./src/workflow-state.ts) for the state model.
3. Read [`src/api-contract.ts`](./src/api-contract.ts) for retry and conflict boundaries.
4. Read [`docs/architecture.md`](./docs/architecture.md) for the system view.
5. Read [`docs/reading-notes.md`](./docs/reading-notes.md) for the engineering and data-analysis takeaways.

## Limitations

The task data is synthetic and the interface is a teaching showcase. Screenshots show blank login screens and isolated workflows with synthetic test accounts. The sample does not claim production scale, institutional deployment, or measured user impact.

## Reproduce locally

From the repository root, run `python -m http.server 8080 --bind 127.0.0.1` and open <http://127.0.0.1:8080/>. No private service, database, or credential is required. Use Node.js 22.18+ or 24+ to run `node --test tests/contracts.test.mjs` for the independent TypeScript workflow and retry examples.
