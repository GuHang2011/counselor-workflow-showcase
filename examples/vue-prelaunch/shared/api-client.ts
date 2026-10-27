import { newOperationId, CLIENT_VERSION } from './domain';
import type { Transport } from './contracts';
export class ApiError extends Error { constructor(message:string,public status:number,public code:unknown){super(message);} }
export function createRequester(transport:Transport,getToken:()=>string,onUnauthorized:()=>void){
  const pending=new Map<string,string>();
  async function request<T=any>(path:string,method='GET',body?:unknown,extra:Record<string,string>={}):Promise<T>{
    const token=getToken();const headers:Record<string,string>={'X-Client-Version':CLIENT_VERSION,...extra};if(token)headers.Authorization=`Bearer ${token}`;
    const mutable=['POST','PUT'].includes(method)&&!path.startsWith('/auth/')&&!path.startsWith('/uploads/')&&!path.startsWith('/files');
    const signature=mutable?`${token}|${method}|${path}|${JSON.stringify(body??null)}`:'';
    if(signature){if(!pending.has(signature))pending.set(signature,newOperationId());headers['Idempotency-Key']=pending.get(signature)!;}
    let response:{status:number;body:any};try{response=await transport.request(path,method,body,headers);}catch{throw new ApiError(mutable?'网络中断，操作结果待确认。重试会核对同一操作，不会重复提交。':'无法连接服务，请检查网络。',0,'NETWORK');}
    if(response.status<200||response.status>=300||response.body?.code!==0){if(signature&&response.status>=400&&response.status<500)pending.delete(signature);if(response.status===401&&path!='/auth/login'&&token===getToken())onUnauthorized();throw new ApiError(response.body?.message||`操作失败（${response.status}）`,response.status,response.body?.code);}
    if(signature)pending.delete(signature);return response.body.data;
  }
  return {request,clear:()=>pending.clear()};
}
