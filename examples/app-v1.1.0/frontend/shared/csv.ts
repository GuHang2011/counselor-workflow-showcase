/** Export untrusted cells as text, including formulas hidden behind whitespace. */
export function csvCell(value: unknown): string {
  let text = value == null ? '' : String(value);
  if (/^[\s\u0000-\u001f]*[=+@-]/u.test(text) || /^[\t\r\n]/u.test(text)) text = "'" + text;
  return '"' + text.replace(/"/g, '""') + '"';
}

export function csvText(rows: unknown[][]): string {
  return '\uFEFF' + rows.map(row => row.map(csvCell).join(',')).join('\r\n');
}
