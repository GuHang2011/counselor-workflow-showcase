# Reviewer entry

## Problem

Administrative requests often move through several roles and states. The case study shows how to make those transitions explicit and readable.

## Contribution

This public selection focuses on the interaction model, state transitions, API contract, architecture explanation, and responsive presentation. It is not a copy of a production system.

## Evidence path

1. Open the [live demo](https://guhang2011.github.io/counselor-workflow-showcase/).
2. Read [`src/workflow-state.ts`](./src/workflow-state.ts) for the state model.
3. Read [`src/api-contract.ts`](./src/api-contract.ts) for retry and conflict boundaries.
4. Read [`docs/architecture.md`](./docs/architecture.md) for the system view.
5. Read [`docs/reading-notes.md`](./docs/reading-notes.md) for the engineering and data-analysis takeaways.

## Limitations

The task data is synthetic and the interface is a teaching showcase. Screenshots show the local application's actual blank login screens. The sample does not claim production scale, institutional deployment, or measured user impact.

## Reproduce locally

From the repository root, run `python -m http.server 8080 --bind 127.0.0.1` and open <http://127.0.0.1:8080/>. No private service, database, or credential is required. Use Node.js 22.18+ or 24+ to run `node --test tests/contracts.test.mjs` for the independent TypeScript workflow and retry examples.
