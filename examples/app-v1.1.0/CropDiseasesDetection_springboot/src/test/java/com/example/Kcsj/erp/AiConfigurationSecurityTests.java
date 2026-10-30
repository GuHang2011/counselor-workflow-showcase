package com.example.Kcsj.erp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static com.example.Kcsj.erp.ErpStore.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiConfigurationSecurityTests {
    ErpStore store;
    AiModelConfiguration configuration;
    Map<String,Object> persisted;
    final ObjectMapper json=new ObjectMapper();
    @BeforeEach void setup(){
        store=mock(ErpStore.class);
        when(store.get("ai_config","current")).thenAnswer(invocation->{if(persisted==null)throw new ApiException(404,"missing");return persisted;});
        doAnswer(invocation->{persisted=new LinkedHashMap<>(invocation.getArgument(1));return null;}).when(store).save(eq("ai_config"),anyMap());
        configuration=config(new RecordCipher("AAAAAAAAAAAAAAAAAAAAAA=="));
    }
    AiModelConfiguration config(RecordCipher cipher){
        AiModelConfiguration value=new AiModelConfiguration(store,cipher);
        ReflectionTestUtils.setField(value,"provider","local");
        ReflectionTestUtils.setField(value,"baseUrl","");
        ReflectionTestUtils.setField(value,"apiKey","");
        ReflectionTestUtils.setField(value,"model","");
        return value;
    }
    Map<String,Object> valid(){return map("provider","school-model","baseUrl","https://model.example.edu.cn/v1","model","new-model","apiKey","synthetic-key");}

    @Test void blankKeyPreservesSavedKeyAndExplicitClearRemovesIt(){
        configuration.configure(valid());
        configuration.configure(map("model","renamed-model","apiKey",""));
        assertEquals("synthetic-key",configuration.current().apiKey);
        assertEquals("renamed-model",configuration.current().model);
        assertFalse(configuration.view(true).containsKey("apiKey"));
        assertFalse(configuration.view(false).containsKey("baseUrl"));
        configuration.configure(map("clearApiKey",true));
        assertEquals("",configuration.current().apiKey);
        assertFalse(configuration.current().ready());
    }
    @Test void keysCannotBePersistedWithoutEncryption(){
        AiModelConfiguration plaintext=config(new RecordCipher(""));
        assertEquals(409,assertThrows(ApiException.class,()->plaintext.configure(valid())).status);
        verify(store,never()).save(eq("ai_config"),anyMap());
    }
    @Test void serverConsentCannotBeEnabledFromBrowserInput(){
        Map<String,Object> input=valid();input.put("allowExternalData",true);
        configuration.configure(input);
        assertFalse(configuration.allowsExternalData());
    }
    @Test void invalidUrlsAndChangedDestinationCannotReceiveExistingKey(){
        configuration.configure(valid());
        for(String url:Arrays.asList("http://external.example/v1","https://user:password@example.com/v1","https://example.com/v1?key=secret","https://example.com/v1#fragment","file:///etc/passwd"))
            assertEquals(400,assertThrows(ApiException.class,()->configuration.configure(map("baseUrl",url))).status);
        assertEquals(400,assertThrows(ApiException.class,()->configuration.configure(map("baseUrl","https://other.example/v1"))).status);
        assertEquals("https://model.example.edu.cn/v1",configuration.current().baseUrl);
    }
    @Test void failedSaveNeverChangesLiveConfiguration(){
        configuration.configure(valid());
        doThrow(new IllegalStateException("disk failure")).when(store).save(eq("ai_config"),anyMap());
        assertThrows(IllegalStateException.class,()->configuration.configure(map("model","unsaved-model")));
        assertEquals("new-model",configuration.current().model);
    }
    @Test void localProviderReportsDisabledAndRestartReadsSavedSettings(){
        configuration.configure(valid());
        AiModelConfiguration restarted=config(new RecordCipher("AAAAAAAAAAAAAAAAAAAAAA=="));
        assertTrue(restarted.current().ready());
        assertEquals("synthetic-key",restarted.current().apiKey);
        configuration.configure(map("provider","local"));
        assertEquals(false,configuration.view(false).get("enabled"));
        assertEquals(false,configuration.view(false).get("modelConfigured"));
    }
    @Test void outboundRequestUsesSavedModelAndKeyWithoutStatusRead() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        AtomicReference<String> auth=new AtomicReference<>(),payload=new AtomicReference<>();
        server.createContext("/v1/chat/completions",exchange->{
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            payload.set(new String(org.springframework.util.StreamUtils.copyToByteArray(exchange.getRequestBody()),StandardCharsets.UTF_8));
            byte[] answer="{\"choices\":[{\"message\":{\"content\":\"synthetic answer\"}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,answer.length);exchange.getResponseBody().write(answer);exchange.close();
        });server.start();
        try {
            Map<String,Object> input=valid();input.put("baseUrl","http://127.0.0.1:"+server.getAddress().getPort()+"/v1");
            configuration.configure(input);
            AiModelConfiguration restarted=config(new RecordCipher("AAAAAAAAAAAAAAAAAAAAAA=="));
            ReflectionTestUtils.setField(restarted,"allowExternalData",true);
            AiAssistantService service=new AiAssistantService(json,restarted);
            Map<String,Object> result=service.assist(map("action","text_write","prompt","synthetic facts"));
            assertEquals("external",result.get("mode"));
            assertEquals("Bearer synthetic-key",auth.get());
            assertEquals("new-model",json.readTree(payload.get()).path("model").asText());
            assertEquals(2000,json.readTree(payload.get()).path("max_tokens").asInt());
        } finally {server.stop(0);}
    }
    @Test void oversizedResponseFallsBackWithoutReturningRawModelData() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange->{
            byte[] oversized=new byte[1024*1024+1];Arrays.fill(oversized,(byte)'a');
            exchange.sendResponseHeaders(200,oversized.length);
            try{exchange.getResponseBody().write(oversized);}finally{exchange.close();}
        });server.start();
        try {
            Map<String,Object> input=valid();input.put("baseUrl","http://127.0.0.1:"+server.getAddress().getPort());configuration.configure(input);
            ReflectionTestUtils.setField(configuration,"allowExternalData",true);
            Map<String,Object> result=new AiAssistantService(json,configuration).assist(map("action","text_write","prompt","synthetic facts"));
            assertEquals("template",result.get("mode"));
            assertTrue(s(result,"warning").contains("未成功"));
        }finally{server.stop(0);}
    }
}
