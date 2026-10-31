const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const moduleObject = { exports: {} };
const source = ts.transpileModule(fs.readFileSync(path.resolve(__dirname, '../shared/college-work.ts'), 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 },
}).outputText;
vm.runInNewContext(source, { module: moduleObject, exports: moduleObject.exports });
const { isRecordOverdue, compareTaskPriority } = moduleObject.exports;
const deadline = Date.parse('2026-10-31T12:00:00Z');

test('an open task becomes overdue when time crosses its deadline without reloading', () => {
  const task = { status: 'in_progress', dueAt: new Date(deadline).toISOString(), overdue: false };
  assert.equal(isRecordOverdue(task, deadline - 1), false);
  assert.equal(isRecordOverdue(task, deadline), true);
  assert.equal(isRecordOverdue(task, deadline + 60000), true);
});
test('submitted and closed records are not counted as overdue', () => {
  for (const status of ['submitted', 'completed', 'cancelled', 'closed', 'archived'])
    assert.equal(isRecordOverdue({ status, dueAt: new Date(deadline).toISOString() }, deadline + 1), false);
});
test('missing dates, invalid dates and future assignments do not create false overdue warnings', () => {
  for (const dueAt of ['', undefined, 'not-a-date']) assert.equal(isRecordOverdue({ status: 'open', dueAt }, deadline), false);
  assert.equal(isRecordOverdue({ status: 'pending', dueAt: new Date(deadline - 1).toISOString(), startsAt: new Date(deadline + 1).toISOString() }, deadline), false);
});

test('urgent work precedes later deadlines and completed work in a mixed task list', () => {
  const rows = [
    { id:'done', status:'completed', dueAt:new Date(deadline-2000).toISOString() },
    { id:'later', status:'pending', dueAt:new Date(deadline+2000).toISOString() },
    { id:'review', status:'submitted', dueAt:new Date(deadline-3000).toISOString() },
    { id:'soon', status:'in_progress', dueAt:new Date(deadline+1000).toISOString() },
    { id:'overdue', status:'returned', dueAt:new Date(deadline-1000).toISOString() },
  ];
  assert.deepEqual(rows.sort((a,b)=>compareTaskPriority(a,b,deadline)).map(r=>r.id), ['overdue','soon','later','review','done']);
});

test('scheduled work and missing deadlines do not displace work that can be handled now', () => {
  const rows = [
    { id:'scheduled', status:'pending', startsAt:new Date(deadline+1000).toISOString(), dueAt:new Date(deadline+2000).toISOString() },
    { id:'undated', status:'pending' },
    { id:'current', status:'pending', dueAt:new Date(deadline+3000).toISOString() },
  ];
  assert.deepEqual(rows.sort((a,b)=>compareTaskPriority(a,b,deadline)).map(r=>r.id), ['current','undated','scheduled']);
});
