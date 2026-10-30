export interface Envelope<T> {code:number|string;data:T;message?:string;msg?:string}
export interface User {id:string;username:string;name:string;role:string;className:string;college?:string;theme:'blue'|'green'|'pink';mustChangePassword:boolean}
export interface BusinessItem {id:string;version?:number;title:string;status:string;dueAt?:string;attachmentIds?:string[];[field:string]:unknown}
export interface Attachment {id:string;name:string;size:number;contentType:string;processingStatus:string;scanStatus:string;processingMessage?:string}
export interface Transport {request(path:string,method:string,body:unknown,headers:Record<string,string>):Promise<{status:number;body:any}>}
