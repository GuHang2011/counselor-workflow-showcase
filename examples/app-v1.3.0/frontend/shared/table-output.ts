import { csvText } from './csv'

export type OutputTable = { title: string; headers: string[]; rows: unknown[][]; note?: string }
export function outputTime(value: unknown): string {
  if (!value) return ''
  const date = new Date(String(value))
  return Number.isFinite(date.getTime()) ? date.toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false }) : String(value)
}
export function saveText(name: string, text: string, type = 'text/plain;charset=utf-8') {
  const url = URL.createObjectURL(new Blob([text], { type }))
  const link = document.createElement('a')
  link.href = url; link.download = name.replace(/[\\/:*?"<>|\u0000-\u001f]/g, '_').slice(0, 180)
  document.body.appendChild(link); link.click(); link.remove()
  setTimeout(() => URL.revokeObjectURL(url), 10000)
}
export function exportTable(table: OutputTable) { saveText(table.title + '.csv', csvText([table.headers, ...table.rows]), 'text/csv;charset=utf-8') }
export function exportTableText(table: OutputTable) {
  saveText(table.title + '.txt', '\ufeff' + [table.title, table.note || '', ...table.rows.map((row, i) => `${i + 1}. ` + table.headers.map((label, column) => `${label}：${row[column] ?? ''}`).join('\r\n'))].join('\r\n\r\n'))
}
let cleanup: (() => void) | undefined
export function clearPrintTable() { cleanup?.() }
/** Build a dedicated print document using text nodes; never interpret cell content as HTML. */
export function printTable(table: OutputTable) {
  cleanup?.()
  const sheet = document.createElement('section'), style = document.createElement('style')
  sheet.id = 'record-print-sheet'; sheet.setAttribute('aria-label', table.title + '打印内容')
  style.textContent = `#record-print-sheet{display:none}@media print{body> :not(#record-print-sheet){display:none!important}html,body{height:auto!important;overflow:visible!important;background:white!important}#record-print-sheet{display:block!important;color:#111;font:11pt "Microsoft YaHei",sans-serif}#record-print-sheet h1{font-size:18pt}#record-print-sheet p{font-size:10pt}#record-print-sheet table{border-collapse:collapse;width:100%;table-layout:fixed}#record-print-sheet th,#record-print-sheet td{border:1px solid #999;padding:6px;text-align:left;white-space:pre-wrap;overflow-wrap:anywhere;vertical-align:top}#record-print-sheet thead{display:table-header-group}#record-print-sheet tr{break-inside:avoid}@page{size:A4 landscape;margin:12mm}}`
  const heading = document.createElement('h1'); heading.textContent = table.title; sheet.appendChild(heading)
  const note = document.createElement('p'); note.textContent = `${table.rows.length} 条记录 · 生成时间：${outputTime(new Date().toISOString())}（北京时间）${table.note ? ' · ' + table.note : ''}`; sheet.appendChild(note)
  const content = document.createElement('table'), head = document.createElement('thead'), body = document.createElement('tbody')
  const addRow = (parent: HTMLElement, values: unknown[], header = false) => {
    const row = document.createElement('tr')
    for (const value of values) { const cell = document.createElement(header ? 'th' : 'td'); cell.textContent = String(value ?? ''); row.appendChild(cell) }
    parent.appendChild(row)
  }
  addRow(head, table.headers, true); for (const values of table.rows) addRow(body, values)
  content.append(head, body); sheet.appendChild(content); document.head.appendChild(style); document.body.appendChild(sheet)
  const restore = () => { sheet.remove(); style.remove(); window.removeEventListener('afterprint', restore); clearTimeout(timeout); if (cleanup === restore) cleanup = undefined }
  const timeout = setTimeout(restore, 120000); cleanup = restore; window.addEventListener('afterprint', restore)
  try { window.print() } catch (error) { restore(); throw error }
}

export function chatOutput(report: { title: string; items: Record<string, unknown>[]; exportedAt: string }): OutputTable {
  return { title: report.title, headers: ['时间（北京时间）', '发送人', '内容 / 表情', '附件名称'],
    rows: report.items.map(item => [outputTime(item.createdAt), item.senderName, item.content, item.attachments]),
    note: '附件仅列名称，原文件请在聊天中下载；表情包以文字说明保留。' }
}
