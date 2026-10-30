const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');

// Exercise the shared production source without generating or committing JS copies.
function loadShared(name, cache = new Map()) {
  if (cache.has(name)) return cache.get(name);
  const filename = path.resolve(__dirname, '..', 'shared', `${name}.ts`);
  const source = ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 },
  }).outputText;
  const module = { exports: {} };
  cache.set(name, module.exports);
  vm.runInNewContext(source, {
    module,
    exports: module.exports,
    require(dependency) {
      if (dependency !== './domain') throw new Error(`Unexpected dependency: ${dependency}`);
      return loadShared('domain', cache);
    },
  }, { filename });
  return module.exports;
}

const { createRequester, ApiError } = loadShared('api-client');
const success = data => ({ status: 200, body: { code: 0, data } });
const unauthorized = () => ({ status: 401, body: { code: 401, message: '会话已失效' } });

test('a delayed 401 from the previous identity does not expire the new session', async () => {
  let token = 'old-identity-token';
  let expireCalls = 0;
  let complete;
  const client = createRequester({
    request() { return new Promise(resolve => { complete = resolve; }); },
  }, () => token, () => { ++expireCalls; token = ''; });

  const pending = client.request('/auth/me');
  token = 'new-identity-token';
  client.clear();
  complete(unauthorized());

  await assert.rejects(pending, error => error instanceof ApiError && error.status === 401);
  assert.equal(expireCalls, 0);
  assert.equal(token, 'new-identity-token');
});

test('a 401 for the current token expires the current session', async () => {
  let token = 'current-token';
  let expireCalls = 0;
  const client = createRequester({ request: async () => unauthorized() }, () => token,
    () => { ++expireCalls; token = ''; });

  await assert.rejects(client.request('/notices'), error => error.status === 401);
  assert.equal(expireCalls, 1);
  assert.equal(token, '');
});

test('a rejected login does not emit a session-expired event', async () => {
  let expireCalls = 0;
  const client = createRequester({ request: async () => unauthorized() }, () => '',
    () => { ++expireCalls; });

  await assert.rejects(client.request('/auth/login', 'POST', { username: 'wrong' }),
    error => error.status === 401);
  assert.equal(expireCalls, 0);
});

test('an uncertain network result retries with the same key until confirmed', async () => {
  const requests = [];
  const body = { title: '通知', content: '请按要求办理' };
  const client = createRequester({
    async request(route, method, payload, headers) {
      requests.push({ route, method, payload, headers });
      if (requests.length === 1) throw new Error('connection lost after send');
      return success({ id: 'notice-1' });
    },
  }, () => 'same-token', () => {});

  await assert.rejects(client.request('/notices', 'POST', body), error => error.code === 'NETWORK');
  assert.equal((await client.request('/notices', 'POST', body)).id, 'notice-1');
  await client.request('/notices', 'POST', body);
  assert.match(requests[0].headers['Idempotency-Key'], /^[A-Za-z0-9_-]{8,100}$/);
  assert.equal(requests[0].headers['Idempotency-Key'], requests[1].headers['Idempotency-Key']);
  assert.notEqual(requests[1].headers['Idempotency-Key'], requests[2].headers['Idempotency-Key']);
});

test('switching token isolates retry keys and authorization for the same operation', async () => {
  let token = 'primary-token';
  const requests = [];
  const client = createRequester({
    async request(route, method, body, headers) {
      requests.push(headers);
      if (requests.length === 1) throw new Error('connection lost');
      return success({ id: 'result-1' });
    },
  }, () => token, () => {});
  const body = { action: 'confirm', version: 1 };

  await assert.rejects(client.request('/erp/receipts/item/action', 'POST', body),
    error => error.code === 'NETWORK');
  token = 'finance-token';
  client.clear();
  await client.request('/erp/receipts/item/action', 'POST', body);
  assert.equal(requests[0].Authorization, 'Bearer primary-token');
  assert.equal(requests[1].Authorization, 'Bearer finance-token');
  assert.notEqual(requests[0]['Idempotency-Key'], requests[1]['Idempotency-Key']);
});

test('an explicit conflict clears the rejected key before a fresh submission', async () => {
  const keys = [];
  const client = createRequester({
    async request(route, method, body, headers) {
      keys.push(headers['Idempotency-Key']);
      return keys.length === 1
        ? { status: 409, body: { code: 409, message: '权限已变化' } }
        : success({ saved: true });
    },
  }, () => 'current-token', () => {});
  const body = { title: '受控操作' };

  await assert.rejects(client.request('/notices', 'POST', body), error => error.status === 409);
  await client.request('/notices', 'POST', body);
  assert.notEqual(keys[0], keys[1]);
});

test('a successful response from a previous identity never returns its private data', async () => {
  let token = 'teacher-A';
  let complete;
  const client = createRequester({request: () => new Promise(resolve => { complete = resolve; })}, () => token, () => {});
  const pending = client.request('/students/private-record');
  token = 'teacher-B';
  client.clear();
  complete(success({ privateNote: 'teacher A only' }));
  await assert.rejects(pending, error => error.code === 'SESSION_CHANGED');
});

test('logout also discards a late successful response and a late login response', async () => {
  for (const route of ['/students', '/auth/login']) {
    let token = route === '/auth/login' ? '' : 'teacher-A';
    let complete;
    const client = createRequester({request: () => new Promise(resolve => { complete = resolve; })}, () => token, () => {});
    const pending = client.request(route);
    token = '';
    client.clear();
    complete(success({ token: 'must-not-restore', privateNote: 'private' }));
    await assert.rejects(pending, error => error.code === 'SESSION_CHANGED');
  }
});
