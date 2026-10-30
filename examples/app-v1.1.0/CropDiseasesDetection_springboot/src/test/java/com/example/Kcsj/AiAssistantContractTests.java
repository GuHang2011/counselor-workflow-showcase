package com.example.Kcsj;

import com.example.Kcsj.erp.AuthService;
import com.example.Kcsj.erp.ErpStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import java.util.*;

import static com.example.Kcsj.erp.ErpStore.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Contract checks for the AI helper. No model or external network is used. */
@SpringBootTest(properties={
        "spring.datasource.url=jdbc:h2:mem:ai-assistant-contract;DB_CLOSE_DELAY=-1",
        "erp.admin-password=",
        "erp.upload-dir=./target/ai-assistant-contract-uploads",
        "erp.ai.enabled=false",
        "erp.ai.provider=local",
        "erp.ai.allow-external-data=false"
})
@AutoConfigureMockMvc
class AiAssistantContractTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ErpStore store;
    @Autowired AuthService auth;
    @Autowired JdbcTemplate jdbc;

    String admin;
    String counselor;
    String student;

    @BeforeEach void seed() {
        jdbc.update("DELETE FROM erp_session");
        jdbc.update("DELETE FROM erp_record");
        admin = session(user("ai-admin", "admin"));
        counselor = session(user("ai-counselor", "counselor"));
        student = session(user("ai-student", "student"));
    }

    @Test void statusRequiresAuthenticationAndDoesNotExposeSecrets() throws Exception {
        call(get("/ai/status"), null, null, 401);
        Map<String,Object> status = data(call(get("/ai/status"), counselor, null, 200));
        assertEquals(Boolean.FALSE, status.get("enabled"));
        assertEquals(Boolean.FALSE, status.get("modelConfigured"));
        assertEquals(Boolean.TRUE, status.get("localFallback"));
        assertEquals(Boolean.TRUE, status.get("requiresHumanReview"));
        assertFalse(status.containsKey("apiKey"));
        assertFalse(status.containsKey("key"));
    }

    @Test void studentCannotUseOrInspectAiHelper() throws Exception {
        call(get("/ai/status"), student, null, 403);
        call(post("/ai/assist"), student, map("scenario", "notice", "topic", "不应越权"), 403);
    }

    @Test void onlySuperAdministratorCanReadOrChangeModelConfiguration() throws Exception {
        call(get("/ai/config"),null,null,401);
        call(get("/ai/config"),counselor,null,403);
        call(put("/ai/config"),counselor,map("provider","local"),403);
        Map<String,Object> configuration=data(call(get("/ai/config"),admin,null,200));
        assertFalse(configuration.containsKey("apiKey"));
        String ordinaryId=user("ordinary-admin","admin");
        Map<String,Object> ordinary=store.get("user",ordinaryId);ordinary.put("adminLevel","administrator");store.save("user",ordinary);
        call(get("/ai/config"),session(ordinaryId),null,403);
    }

    @Test void disabledModelStillReturnsSafeLocalTemplate() throws Exception {
        Map<String,Object> result = data(call(post("/ai/assist"), counselor,
                map("scenario", "notice", "topic", "班会提醒", "details", "请按时参加"), 200));
        assertEquals("template", result.get("mode"));
        assertEquals(Boolean.FALSE, result.get("modelConfigured"));
        assertEquals(Boolean.TRUE, result.get("requiresHumanReview"));
        assertTrue(String.valueOf(result.get("warning")).contains("关闭"));
        assertFalse(String.valueOf(result.get("content")).contains("api_key"));
    }

    @Test void noticeShortcutStillUsesTheSameSafeContract() throws Exception {
        Map<String,Object> result = data(call(post("/ai/notice-draft"), counselor,
                map("subject", "材料提醒", "audience", "一班", "action", "请在周五前完成"), 200));
        assertEquals("template", result.get("mode"));
        assertEquals(Boolean.TRUE, result.get("requiresHumanReview"));
        assertTrue(result.containsKey("title"));
        assertTrue(result.containsKey("content"));
        assertTrue(String.valueOf(result.get("warning")).contains("关闭"));
        assertTrue(String.valueOf(result.get("content")).contains("请在周五前完成"));
    }

    @Test void chatUsesDetailsWhenTheDesktopFormHasNoTopic() throws Exception {
        Map<String,Object> result = data(call(post("/ai/assist"), counselor,
                map("scenario", "chat", "details", "通知应该在哪里发布？"), 200));
        assertTrue(String.valueOf(result.get("content")).contains("通知草稿"));
    }

    @Test void invalidScenarioAndEmptyInputAreRejected() throws Exception {
        call(post("/ai/assist"), counselor, map("scenario", "unsupported", "topic", "测试"), 400);
        call(post("/ai/assist"), counselor, map("scenario", "notice"), 400);
    }

    String user(String username, String role) {
        String id = id();
        store.save("user", map("id", id, "username", username, "name", username,
                "role", role, "className", "一班", "enabled", true,
                "mustChangePassword", false, "createdAt", now()));
        return id;
    }

    String session(String userId) {
        return s(auth.issueSession(store.get("user", userId), "primary", "ai-contract"), "token");
    }

    MvcResult call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                   String token, Map<String,Object> body, int expected) throws Exception {
        request.header("Authorization", token == null ? "" : "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(request).andExpect(status().is(expected)).andReturn();
    }

    @SuppressWarnings("unchecked") Map<String,Object> data(MvcResult result) throws Exception {
        Map<String,Object> envelope = json.readValue(result.getResponse().getContentAsByteArray(), new TypeReference<Map<String,Object>>() {});
        assertEquals(0, ((Number) envelope.get("code")).intValue());
        return (Map<String,Object>) envelope.get("data");
    }
}
