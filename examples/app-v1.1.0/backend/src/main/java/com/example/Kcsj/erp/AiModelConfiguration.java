package com.example.Kcsj.erp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.util.*;
import static com.example.Kcsj.erp.ErpStore.*;

/** One immutable configuration per request; credentials never appear in public views. */
@Component
public class AiModelConfiguration {
    private final ErpStore store;
    private final RecordCipher cipher;
    @Value("${erp.ai.enabled:false}") private boolean enabled;
    @Value("${erp.ai.provider:local}") private String provider;
    @Value("${erp.ai.base-url:}") private String baseUrl;
    @Value("${erp.ai.api-key:}") private String apiKey;
    @Value("${erp.ai.model:}") private String model;
    @Value("${erp.ai.allow-external-data:false}") private boolean allowExternalData;
    private volatile Settings saved;

    public AiModelConfiguration(ErpStore store,RecordCipher cipher){this.store=store;this.cipher=cipher;}

    public synchronized Settings current(){
        if(saved!=null)return saved;
        try {
            Map<String,Object> row=store.get("ai_config","current");
            saved=new Settings(!s(row,"provider").equalsIgnoreCase("local"),s(row,"provider"),s(row,"baseUrl"),s(row,"model"),s(row,"apiKey"));
        } catch(ApiException e) {
            if(e.status!=404)throw e;
            saved=new Settings(enabled,provider,baseUrl,model,apiKey);
        }
        return saved;
    }

    public synchronized Map<String,Object> configure(Map<String,Object> input){
        Settings before=current();
        String nextProvider=field(input,"provider",before.provider,80);
        String nextBase=field(input,"baseUrl",before.baseUrl,300);
        String nextModel=field(input,"model",before.model,120);
        String enteredKey=field(input,"apiKey","",500);
        boolean clear=Boolean.TRUE.equals(input.get("clearApiKey"));
        if(clear&&!enteredKey.isEmpty())throw new ApiException(400,"请分别操作更换密钥与清除密钥");
        if(nextProvider.isEmpty())throw new ApiException(400,"请选择服务商，停用模型请填写 local");
        if(!nextBase.isEmpty()&&!validBaseUrl(nextBase))throw new ApiException(400,"API 地址须为 HTTPS（本机模型可用 HTTP），且不能含账号密码、查询参数或片段");
        boolean external=!nextProvider.equalsIgnoreCase("local");
        if(external&&(nextBase.isEmpty()||nextModel.isEmpty()))throw new ApiException(400,"使用模型须填写 API 地址和模型名称");
        if(!nextBase.equals(before.baseUrl)&&!before.apiKey.isEmpty()&&enteredKey.isEmpty()&&!clear)
            throw new ApiException(400,"API 地址已变化，请重新填写新服务的密钥，或勾选清除密钥");
        String nextKey=clear?"":enteredKey.isEmpty()?before.apiKey:enteredKey;
        if(!nextKey.isEmpty()&&!cipher.enabled())throw new ApiException(409,"服务器尚未配置数据加密，请先由运维设置 ERP_DATA_KEY 后再保存密钥");
        Settings next=new Settings(external,nextProvider,nextBase,nextModel,nextKey);
        // Publish to callers only after persistence succeeds.
        store.save("ai_config",map("id","current","provider",next.provider,"baseUrl",next.baseUrl,"model",next.model,"apiKey",next.apiKey,"updatedAt",now()));
        saved=next;
        return view(true);
    }

    public Map<String,Object> view(boolean admin){
        Settings value=current();
        Map<String,Object> result=map("enabled",value.enabled,"provider",value.provider,"model",value.model,
            "modelConfigured",value.ready(),"localFallback",true,"externalDataAllowed",allowExternalData,"requiresHumanReview",true);
        if(admin){result.put("baseUrl",value.baseUrl);result.put("apiKeyConfigured",!value.apiKey.isEmpty());result.put("encryptedStorage",cipher.enabled());}
        return result;
    }

    public boolean allowsExternalData(){return allowExternalData;}
    private String field(Map<String,Object> body,String key,String fallback,int max){
        if(!body.containsKey(key))return fallback;
        if(!(body.get(key) instanceof String))throw new ApiException(400,"AI 配置字段格式无效");
        String value=((String)body.get(key)).trim();
        if(value.length()>max||value.matches("(?s).*[\\x00-\\x1F\\x7F].*"))throw new ApiException(400,"AI 配置字段长度或格式无效");
        return value;
    }
    private static boolean validBaseUrl(String value){
        try {
            URI uri=URI.create(value);String host=uri.getHost();
            if(host==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||uri.getPort()==0)return false;
            return "https".equalsIgnoreCase(uri.getScheme())||("http".equalsIgnoreCase(uri.getScheme())&&
                (host.equalsIgnoreCase("localhost")||host.equals("127.0.0.1")||host.equals("[::1]")||host.equals("::1")));
        }catch(IllegalArgumentException e){return false;}
    }
    public static final class Settings {
        final boolean enabled;final String provider,baseUrl,model,apiKey;
        Settings(boolean enabled,String provider,String baseUrl,String model,String apiKey){
            this.enabled=enabled;this.provider=provider;this.baseUrl=baseUrl;this.model=model;this.apiKey=apiKey;
        }
        boolean ready(){return enabled&&!provider.equalsIgnoreCase("local")&&!model.isEmpty()&&!apiKey.isEmpty()&&validBaseUrl(baseUrl);}
        String endpoint(){String root=baseUrl.replaceAll("/+$","");return root.endsWith("/v1")?root+"/chat/completions":root+"/v1/chat/completions";}
    }
}
