import { createRequester } from '../shared/api-client';
const TOKEN_KEY='counselor_erp_token';
export const getToken=()=>sessionStorage.getItem(TOKEN_KEY)||'';
export const setToken=(token:string)=>{client.clear();if(token)sessionStorage.setItem(TOKEN_KEY,token);else sessionStorage.removeItem(TOKEN_KEY);};
export type ApiOptions=Omit<RequestInit,'body'> & {body?:any};
const client=createRequester({async request(path,method,body,headers){
  const controller=new AbortController();const timer=setTimeout(()=>controller.abort(),180000);
  try{const multipart=body instanceof FormData;const response=await fetch(path.startsWith('/api/')?path:`/api${path}`,{method,headers:multipart?headers:{'Content-Type':'application/json',...headers},body:body===undefined?undefined:multipart?body:typeof body==='string'?body:JSON.stringify(body),signal:controller.signal});return {status:response.status,body:await response.json().catch(()=>null)};}finally{clearTimeout(timer);}
}},getToken,()=>window.dispatchEvent(new Event('session-expired')));
export async function api<T=any>(path:string,options:ApiOptions={}):Promise<T>{const headers:Record<string,string>={};new Headers(options.headers).forEach((value,key)=>headers[key]=value);return client.request<T>(path,options.method?.toUpperCase()||'GET',options.body,headers);}
