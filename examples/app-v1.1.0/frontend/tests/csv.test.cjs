const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const ts = require('typescript');
const source = ts.transpileModule(fs.readFileSync(path.resolve(__dirname, '../shared/csv.ts'), 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 },
}).outputText;
const moduleObject = {exports:{}};
vm.runInNewContext(source, {module:moduleObject,exports:moduleObject.exports});
const {csvCell,csvText}=moduleObject.exports;

test('spreadsheet formulas, including whitespace and control prefixes, stay text', () => {
  for (const value of ['=1+1','+SUM(1,2)','-1+2','@SUM(1,2)','  =1+1','\uFEFF=1+1','\t=1+1','\r=1+1','\n=1+1','\u0000=1+1','\t123'])
    assert.equal(csvCell(value).startsWith('"\''),true,JSON.stringify(value));
});

test('ordinary text, quotes, newlines, nulls and Chinese export without data loss', () => {
  assert.equal(csvText([['姓名','备注'],['示例学生','两行\n含"引号"和,逗号'],[null,42]]),
    '\uFEFF"姓名","备注"\r\n"示例学生","两行\n含""引号""和,逗号"\r\n"","42"');
  assert.equal(csvCell('001234'),'"001234"');
});
