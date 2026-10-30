export const SYSTEM_NAME = '智能工程技术学院辅导员学工工作台';
export const CLIENT_VERSION = '1.1.0';
export const THEMES = ['blue', 'green', 'pink'] as const;
export const statusNames: Record<string,string> = {draft:'草稿',pending:'待处理',submitted:'已提交',returned:'待补正',approved:'已批准',rejected:'已驳回',completed:'已完成',cancelled:'已撤销',closed:'已办结',exception:'待人工核验',in_progress:'办理中',published:'已发布',scheduled:'待发布',withdrawn:'已撤回',ready:'可使用',quarantined:'待安全检查'};
export function passwordValid(value:string){return value.length>=12&&value.length<=128&&/[A-Za-z]/.test(value)&&/[0-9]/.test(value);}
export function csvCell(value:unknown){let text=String(value??'');if(/^[\s]*[=+\-@]/.test(text))text="'"+text;return '"'+text.replace(/"/g,'""')+'"';}
export function newOperationId(){return Date.now().toString(36)+'-'+Array.from({length:6},()=>Math.floor(Math.random()*0xffffffff).toString(16).padStart(8,'0')).join('');}
