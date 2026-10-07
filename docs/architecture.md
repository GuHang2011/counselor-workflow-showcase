# Architecture notes

## A small, inspectable boundary map

This is a design explanation for the larger application. The published demo itself is static HTML/CSS/JavaScript; it has no API, account system or persistent workflow. The TypeScript samples illustrate selected domain rules independently of the page.

```text
Role-aware Vue view
        ↓  typed request + idempotency key
HTTP API boundary
        ↓  authorization, version check, business rule
Workflow domain service
        ↓  event / audit record
Relational persistence + protected attachment storage
```

The important design choice is to keep **permission**, **workflow state** and **presentation** separate. A button can be hidden in the UI for convenience, but the server still checks the actor, the scope and the current version before changing data.

## Interaction patterns

- A task is shown with an owner, status, due date and source.
- In `applyAction`, only the current owner may transfer. A transfer requires a different next owner, increments the version and returns to `pending`; the next owner then accepts. The server must also check that the target exists and is eligible for the task.
- Retaining submission history, correction reasons and audit records are responsibilities of a complete backend; those storage features are not implemented by the pure example.
- A mutation is eligible for retry only when the exact method/path explicitly supports server-side deduplication and a stable idempotency key is present. A key without a server contract is insufficient. Retries must preserve the payload and key, use a bounded backoff, and must not retry authorization, validation or version conflicts automatically.
- The server needs atomic version checks, authorization and deduplication storage in the same mutation boundary. This repository demonstrates client/domain checks, not those server guarantees.
- Empty, loading, error and unauthorized states should be covered in a connected application; this static page only changes illustrative text.

## Why this matters for a dense admin interface

The interface is not a collection of decorative cards. It is a reading surface for work: current responsibility, next action, evidence and history should be visible in the same rhythm. The online page demonstrates this idea with role tabs and module filters.
