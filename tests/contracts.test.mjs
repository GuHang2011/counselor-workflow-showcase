import assert from 'node:assert/strict';
import test from 'node:test';
import { applyAction } from '../src/workflow-state.ts';
import { buildMutationContext, isRetryableMutation } from '../src/api-contract.ts';

const item = { id: 'demo-001', status: 'in_progress', ownerId: 'owner-a', version: 2 };

test('only the current owner can act, including transferring a request', () => {
  for (const action of ['return', 'complete', 'transfer']) {
    assert.throws(() => applyAction(item, action, 'other-user', 2, 'owner-b'), /current owner/);
  }
});

test('a transfer changes ownership, returns to pending and preserves the input', () => {
  const transferred = applyAction(item, 'transfer', 'owner-a', 2, 'owner-b');
  assert.deepEqual(transferred, { ...item, status: 'pending', ownerId: 'owner-b', version: 3 });
  assert.equal(item.ownerId, 'owner-a');
  assert.equal(item.version, 2);
  assert.throws(() => applyAction(transferred, 'accept', 'owner-a', 3), /current owner/);
  assert.equal(applyAction(transferred, 'accept', 'owner-b', 3).status, 'in_progress');
});

test('invalid targets, stale writes, impossible transitions and invalid versions fail', () => {
  for (const target of [undefined, '', ' ', 'owner-a', ' owner-b ']) {
    assert.throws(() => applyAction(item, 'transfer', 'owner-a', 2, target), /next owner/);
  }
  assert.throws(() => applyAction(item, 'complete', 'owner-a', 1), /changed/);
  assert.throws(() => applyAction(item, 'close', 'owner-a', 2), /not allowed/);
  assert.throws(() => applyAction(item, 'complete', 'owner-a', 2, 'owner-b'), /Only a transfer/);
  for (const version of [-1, 1.5, Number.MAX_SAFE_INTEGER]) {
    assert.throws(() => applyAction({ ...item, version }, 'complete', 'owner-a', version), /version/);
  }
});

test('retry requires a stable key and an explicit contract for this method and endpoint', () => {
  const context = buildMutationContext('example-token', 'demo-v1', 'operation-001');
  const contract = { method: 'POST', path: '/requests', serverDeduplication: true };
  assert.equal(isRetryableMutation('post', '/requests', context, contract), true);
  assert.equal(isRetryableMutation('POST', '/requests', context), false);
  assert.equal(isRetryableMutation('POST', '/requests', context, { ...contract, serverDeduplication: false }), false);
  assert.equal(isRetryableMutation('POST', '/requests', { ...context, idempotencyKey: '' }, contract), false);
  assert.equal(isRetryableMutation('POST', '/requests', { ...context, idempotencyKey: ' ' }, contract), false);
  assert.equal(isRetryableMutation('POST', '/other', context, contract), false);
  assert.equal(isRetryableMutation('PATCH', '/requests', context, contract), false);
  assert.equal(isRetryableMutation('GET', '/requests', context, contract), false);
});
