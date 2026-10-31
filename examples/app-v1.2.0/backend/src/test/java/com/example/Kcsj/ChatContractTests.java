package com.example.Kcsj;

import com.example.Kcsj.erp.AuthService;
import com.example.Kcsj.erp.ChatService;
import com.example.Kcsj.erp.ErpStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static com.example.Kcsj.erp.ErpStore.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:chat-contract;DB_CLOSE_DELAY=-1", "erp.admin-password=", "erp.upload-dir=./target/chat-test-uploads"})
@AutoConfigureMockMvc
class ChatContractTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ErpStore store;
    @Autowired AuthService auth;
    @Autowired ChatService chat;
    @Autowired JdbcTemplate jdbc;
    private String counselorToken, studentToken, otherCounselorToken, adminToken, businessToken;

    @BeforeEach void prepare() {
        jdbc.update("DELETE FROM erp_session"); jdbc.update("DELETE FROM erp_record");
        counselorToken = user("c1", "counselor", "学院A", "一班");
        studentToken = user("s1", "student", "学院A", "一班");
        otherCounselorToken = user("c2", "counselor", "学院B", "二班");
        adminToken = user("a1", "admin", "学院B", ""); businessToken = user("b1", "business", "学院A", "");
        user("s2", "student", "学院B", "二班"); user("b2", "business", "学院B", ""); user("empty", "business", "", "");
    }

    @Test void contactsFollowCurrentStudentAndStaffScopes() throws Exception {
        assertEquals(new HashSet<>(Arrays.asList("c1")), recordIds(list(call(get("/chat/contacts"), studentToken, null, 200))));
        Set<String> ids = recordIds(list(call(get("/chat/contacts"), counselorToken, null, 200)));
        assertTrue(ids.containsAll(Arrays.asList("s1", "b1", "a1")));
        for (String id : Arrays.asList("s2", "c2", "empty")) assertFalse(ids.contains(id));
        call(post("/chat/conversations"), studentToken, map("recipientId", "a1"), 403);
        call(post("/chat/conversations"), adminToken, map("recipientId", "s1"), 403);
        call(post("/chat/conversations"), counselorToken, map("recipientId", "c1"), 403);
        call(post("/chat/conversations"), counselorToken, map("recipientId", "missing"), 403);
        for (Map<String,Object> contact : list(call(get("/chat/contacts"), counselorToken, null, 200))) assertEquals(new HashSet<>(Arrays.asList("id","name","role","className","college")), contact.keySet());
    }
    @Test void anonymousRequestsCannotReadOrSend() throws Exception {
        for (String path : Arrays.asList("/chat/contacts","/chat/conversations","/chat/unread")) call(get(path), null, null, 401);
        call(post("/chat/conversations"), null, map("recipientId", "c1"), 401);
    }
    @Test void repeatedOpenReusesOneConversationAndOtherAdminsCannotRead() throws Exception {
        String id = open(counselorToken,"s1"); assertEquals(id,open(studentToken,"c1")); assertEquals(1,store.all("chat_conversation").size());
        send(counselorToken,id,"m1","私密消息");
        for (String token : Arrays.asList(adminToken,businessToken,otherCounselorToken)) {
            call(get(path(id)),token,null,403); call(post(path(id)),token,map("clientMessageId","evil","content","越权"),403);
            call(post("/chat/conversations/"+id+"/read"),token,map("throughSequence",1),403);
        }
        assertTrue(list(call(get("/chat/conversations"),adminToken,null,200)).isEmpty());
    }
    @Test void changingScopeRevokesHistorySendingAndCachedRetries() throws Exception {
        String id=open(counselorToken,"s1"); Map<String,Object> body=map("clientMessageId","scope-retry","content","第一次");
        call(post(path(id)).header("Idempotency-Key","stable-retry"),counselorToken,body,200);
        Map<String,Object> student=store.get("user","s1"); student.put("className","二班"); store.save("user",student);
        call(get(path(id)),counselorToken,null,403); call(post(path(id)).header("Idempotency-Key","stable-retry"),counselorToken,body,403); call(get(path(id)),studentToken,null,403);
        assertTrue(list(call(get("/chat/conversations"),counselorToken,null,200)).isEmpty());
        assertEquals(0L,n(object(call(get("/chat/unread"),studentToken,null,200)).get("unreadCount")));
    }
    @Test void inactivePeerAndRevokedDelegationsCannotKeepConversationAccess() throws Exception {
        store.save("scope_delegation",map("id","d1","assigneeId","c2","className","一班","revoked",false,"from","2020-01-01T00:00:00Z","until","2099-01-01T00:00:00Z"));
        String delegated=open(otherCounselorToken,"s1"); Map<String,Object> delegation=store.get("scope_delegation","d1"); delegation.put("revoked",true); store.save("scope_delegation",delegation);
        call(get(path(delegated)),otherCounselorToken,null,403);
        String id=open(counselorToken,"s1"); Map<String,Object> student=store.get("user","s1"); student.put("enabled",false); store.save("user",student);
        call(get(path(id)),counselorToken,null,403); call(post(path(id)),counselorToken,map("clientMessageId","inactive","content","停止"),403); call(post("/chat/conversations"),counselorToken,map("recipientId","s1"),403);
    }
    @Test void bundledStickersHaveReadableHistoryAndCanAccompanyText() throws Exception {
        String id = open(counselorToken,"s1");
        for (String sticker : Arrays.asList("received","okay","thanks","hard-work","cheer","great","wait","done","check-files","together","morning","sorry")) {
            Map<String,Object> sent = object(call(post(path(id)),counselorToken,map("clientMessageId","sticker-"+sticker,"stickerId",sticker),200));
            assertEquals(sticker,sent.get("stickerId"));
            assertTrue(s(sent,"content").startsWith("[表情包："));
            assertTrue(((List<?>)sent.get("attachmentIds")).isEmpty());
        }
        Map<String,Object> combined = object(call(post(path(id)),studentToken,map("clientMessageId","text-and-sticker","content","老师辛苦了 😊","stickerId","thanks"),200));
        assertEquals("老师辛苦了 😊",combined.get("content"));
        List<Map<String,Object>> history = list(object(call(get(path(id)),studentToken,null,200)).get("items"));
        assertEquals(13,history.size()); assertEquals("received",history.get(0).get("stickerId"));
        assertEquals("thanks",object(list(call(get("/chat/conversations"),counselorToken,null,200)).get(0).get("lastMessage")).get("stickerId"));
    }
    @Test void stickerRetriesAreIdempotentAndChangedStickerConflicts() throws Exception {
        String id=open(counselorToken,"s1");
        Map<String,Object> body=map("clientMessageId","sticker-retry","stickerId","received");
        Map<String,Object> first=object(call(post(path(id)),counselorToken,body,200));
        assertEquals(first.get("id"),object(call(post(path(id)),counselorToken,body,200)).get("id"));
        call(post(path(id)),counselorToken,map("clientMessageId","sticker-retry","stickerId","thanks"),409);
        call(post(path(id)),counselorToken,map("clientMessageId","sticker-retry","content",first.get("content")),409);
        assertEquals(1,store.all("chat_message").size());
        call(post(path(id)),adminToken,map("clientMessageId","foreign-sticker","stickerId","received"),403);
        Map<String,Object> student=store.get("user","s1"); student.put("className","二班"); store.save("user",student);
        call(post(path(id)),counselorToken,body,403);
    }
    @Test void stickersRejectUnknownIdentifiersAndDoNotBypassFileValidation() throws Exception {
        String id=open(counselorToken,"s1");
        for(Object value:Arrays.asList("unknown","https://example.org/pixel.png","../received"," received",123,Arrays.asList("received")))
            call(post(path(id)),counselorToken,map("clientMessageId","invalid-sticker","content","正文","stickerId",value),400);
        file("not-ready","c1","quarantined","");
        call(post(path(id)),counselorToken,map("clientMessageId","unsafe-file","stickerId","okay","attachmentIds",Arrays.asList("not-ready")),400);
        assertTrue(store.all("chat_message").isEmpty());
        Map<String,Object> plain=send(counselorToken,id,"old-client","旧版纯文字 👍");
        assertEquals("",s(plain,"stickerId")); assertEquals("旧版纯文字 👍",plain.get("content"));
    }
    @Test void validatesPlainTextAndAttachmentLimits() throws Exception {
        String id=open(counselorToken,"s1");
        call(post(path(id)),counselorToken,map("clientMessageId","empty","content","  "),400);
        call(post(path(id)),counselorToken,map("content","缺少重试标识"),400);
        call(post(path(id)),counselorToken,map("clientMessageId","number","content",123),400);
        call(post(path(id)),counselorToken,map("clientMessageId","long","content",new String(new char[2001]).replace('\0','x')),400);
        List<String> many=new ArrayList<>(); for(int i=0;i<11;i++) many.add("f-"+i);
        call(post(path(id)),counselorToken,map("clientMessageId","many","attachmentIds",many),400);
        call(post(path(id)),counselorToken,map("clientMessageId","bad-list","attachmentIds","f1"),400);
        call(post(path(id)),counselorToken,map("clientMessageId","duplicates","attachmentIds",Arrays.asList("f1","f1")),400);
        assertEquals("<script>alert(1)</script>",send(counselorToken,id,"literal","<script>alert(1)</script>").get("content"));
    }
    @Test void onlyReadyUnexpiredOwnFilesCanBeSharedAndOnlyCurrentParticipantsCanRead() throws Exception {
        String id=open(counselorToken,"s1"); file("ready","c1","ready",""); file("queued","c1","queued",""); file("rejected","c1","rejected",""); file("foreign","b1","ready",""); file("expired","c1","ready","2020-01-01T00:00:00Z");
        assertFalse(chat.canRead(store.get("user","s1"),"ready"));
        call(post(path(id)),counselorToken,attachment("q","queued"),400); call(post(path(id)),counselorToken,attachment("r","rejected"),400); call(post(path(id)),counselorToken,attachment("f","foreign"),403); call(post(path(id)),counselorToken,attachment("e","expired"),410); call(post(path(id)),counselorToken,attachment("m","missing"),400);
        assertEquals(Arrays.asList("ready"),object(call(post(path(id)),counselorToken,attachment("ready-message","ready"),200)).get("attachmentIds"));
        assertTrue(chat.canRead(store.get("user","s1"),"ready")); assertFalse(chat.canRead(store.get("user","a1"),"ready")); assertFalse(chat.canRead(store.get("user","b1"),"ready"));
        Map<String,Object> restricted=store.get("user","s1"); restricted.put("roleRestricted",true); assertFalse(chat.canRead(restricted,"ready"));
        Map<String,Object> student=store.get("user","s1"); student.put("className","二班"); store.save("user",student); assertFalse(chat.canRead(student,"ready"));
    }
    @Test void retryIsIdempotentButDifferentContentConflictsAndSenderKeysAreIndependent() throws Exception {
        String id=open(counselorToken,"s1"); Map<String,Object> first=send(counselorToken,id,"retry-key","原文"), retry=send(counselorToken,id,"retry-key","原文");
        assertEquals(first.get("id"),retry.get("id")); assertEquals(first.get("sequence"),retry.get("sequence")); assertEquals(1,store.all("chat_message").size());
        call(post(path(id)),counselorToken,map("clientMessageId","retry-key","content","修改后的内容"),409); assertEquals(2L,n(send(studentToken,id,"retry-key","回复").get("sequence")));
    }
    @Test void readCursorIsMonotonicAndCannotMarkMessagesNewerThanDisplayedSnapshot() throws Exception {
        String id=open(counselorToken,"s1"); send(counselorToken,id,"read1","第一条"); send(counselorToken,id,"read2","第二条"); String readPath="/chat/conversations/"+id+"/read";
        assertEquals(1L,n(object(call(post(readPath),studentToken,map("throughSequence",1),200)).get("readSequence"))); assertEquals(1L,n(object(call(post(readPath),studentToken,map("throughSequence",0),200)).get("readSequence")));
        long displayed=n(object(call(get(path(id)),studentToken,null,200)).get("latestSequence")); send(counselorToken,id,"read3","稍后到达"); call(post(readPath),studentToken,map("throughSequence",displayed),200);
        assertEquals(1L,n(object(call(get("/chat/unread"),studentToken,null,200)).get("unreadCount")));
        for(Object value:Arrays.asList(4,-1,1.5)) call(post(readPath),studentToken,map("throughSequence",value),400);
        assertEquals(2L,n(list(call(get("/chat/conversations"),counselorToken,null,200)).get(0).get("peerReadSequence"))); assertEquals(0L,n(object(call(get("/chat/unread"),counselorToken,null,200)).get("unreadCount")));
    }
    @Test void historyPaginationIsStableAndRejectsInvalidBounds() throws Exception {
        String id=open(counselorToken,"s1"); for(int i=1;i<=5;i++) send(counselorToken,id,"page-"+i,"消息"+i);
        Map<String,Object> first=object(call(get(path(id)+"?limit=2"),studentToken,null,200)); assertEquals(Arrays.asList(4L,5L),sequences(first.get("items"))); assertEquals(Boolean.TRUE,first.get("hasMore")); assertEquals(4L,n(first.get("nextBefore")));
        assertEquals(Arrays.asList(2L,3L),sequences(object(call(get(path(id)+"?limit=2&beforeSequence=4"),studentToken,null,200)).get("items")));
        Map<String,Object> last=object(call(get(path(id)+"?limit=2&beforeSequence=2"),studentToken,null,200)); assertEquals(Arrays.asList(1L),sequences(last.get("items"))); assertEquals(Boolean.FALSE,last.get("hasMore")); assertNull(last.get("nextBefore")); assertEquals(5L,n(last.get("latestSequence")));
        for(String query:Arrays.asList("?limit=0","?limit=101","?limit=bad","?limit=999999999999","?beforeSequence=0","?beforeSequence=-1","?beforeSequence=bad","?beforeSequence=999999999999999999999")) call(get(path(id)+query),studentToken,null,400);
    }
    @Test void lockedFutureExpiredAndUnactivatedPeersCannotReceiveMessages() throws Exception {
        String id=open(counselorToken,"s1"); Map<String,Object> original=new LinkedHashMap<>(store.get("user","s1"));
        for(Map<String,Object> restriction:Arrays.asList(map("locked",true),map("validFrom","2099-01-01T00:00:00Z"),map("validUntil","2020-01-01T00:00:00Z"),map("mustChangePassword",true))) {
            Map<String,Object> peer=new LinkedHashMap<>(original); peer.putAll(restriction); store.save("user",peer);
            call(post(path(id)),counselorToken,map("clientMessageId","restricted-peer","content","不应发送"),403); assertFalse(recordIds(list(call(get("/chat/contacts"),counselorToken,null,200))).contains("s1"));
        }
    }
    @Test void concurrentWritesHaveUniqueSequencesAndOneRecordForConcurrentRetry() throws Exception {
        String id=open(counselorToken,"s1"); ExecutorService pool=Executors.newFixedThreadPool(4); List<Future<Map<String,Object>>> results=new ArrayList<>();
        try {
            for(int i=0;i<6;i++) { final int index=i; results.add(pool.submit(() -> chat.send(request(counselorToken,path(id)),id,map("clientMessageId","parallel-"+index,"content","消息"+index)))); }
            for(int i=0;i<2;i++) results.add(pool.submit(() -> chat.send(request(counselorToken,path(id)),id,map("clientMessageId","same-parallel-key","content","重复请求"))));
            Set<String> ids=new HashSet<>(); for(Future<Map<String,Object>> result:results) ids.add(s(result.get(30,TimeUnit.SECONDS),"id"));
            assertEquals(7,ids.size()); assertEquals(Arrays.asList(1L,2L,3L,4L,5L,6L,7L),sequences(object(call(get(path(id)),studentToken,null,200)).get("items"))); assertEquals(7,store.all("chat_message").size());
        } finally { pool.shutdownNow(); }
    }
    @Test void completedSendRetrySurvivesAttachmentExpiryOrQuarantineWithoutRestoringFileAccess() throws Exception {
        String id = open(counselorToken, "s1"); file("retry-file", "c1", "ready", "");
        Map<String,Object> body = attachment("completed-send", "retry-file");
        Map<String,Object> original = object(call(post(path(id)), counselorToken, body, 200));
        Map<String,Object> metadata = store.get("file", "retry-file");
        metadata.put("expiresAt", "2020-01-01T00:00:00Z"); store.save("file", metadata);
        Map<String,Object> retry = object(call(post(path(id)), counselorToken, body, 200));
        assertEquals(original.get("id"), retry.get("id")); assertEquals(original.get("sequence"), retry.get("sequence"));
        assertFalse(chat.canRead(store.get("user", "s1"), "retry-file"));
        call(post(path(id)), counselorToken, attachment("new-expired-send", "retry-file"), 410);
        metadata.put("expiresAt", ""); metadata.put("processingStatus", "quarantined"); store.save("file", metadata);
        retry = object(call(post(path(id)), counselorToken, body, 200)); assertEquals(original.get("id"), retry.get("id"));
        assertFalse(chat.canRead(store.get("user", "s1"), "retry-file"));
        call(post(path(id)), counselorToken, attachment("new-quarantined-send", "retry-file"), 400);
        Map<String,Object> changed = new LinkedHashMap<>(body); changed.put("content", "改变后的正文");
        call(post(path(id)), counselorToken, changed, 409); assertEquals(1, store.all("chat_message").size());
    }
    private String user(String uid,String role,String college,String clazz) { store.save("user",map("id",uid,"username",uid,"name",uid,"role",role,"college",college,"className",clazz,"enabled",true,"mustChangePassword",false,"createdAt",now())); return s(auth.issueSession(store.get("user",uid),"primary","test"),"token"); }
    private void file(String id,String owner,String state,String expiry) { store.save("file",map("id",id,"ownerId",owner,"name",id+".txt","processingStatus",state,"expiresAt",expiry)); }
    private Map<String,Object> attachment(String key,String file) { return map("clientMessageId",key,"attachmentIds",Arrays.asList(file)); }
    private String open(String token,String recipient) throws Exception { return s(object(call(post("/chat/conversations"),token,map("recipientId",recipient),200)),"id"); }
    private Map<String,Object> send(String token,String id,String key,String content) throws Exception { return object(call(post(path(id)),token,map("clientMessageId",key,"content",content),200)); }
    private Object call(MockHttpServletRequestBuilder builder,String token,Map<String,Object> body,int expected) throws Exception { if(token!=null) builder.header("Authorization","Bearer "+token); if(body!=null) builder.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)); MvcResult result=mvc.perform(builder).andExpect(status().is(expected)).andReturn(); if(expected!=200) return null; Map<?,?> payload=json.readValue(result.getResponse().getContentAsByteArray(),Map.class); assertEquals(0L,n(payload.get("code"))); return payload.get("data"); }
    private static MockHttpServletRequest request(String token,String uri) { MockHttpServletRequest request=new MockHttpServletRequest("POST",uri); request.addHeader("Authorization","Bearer "+token); return request; }
    private static String path(String id) { return "/chat/conversations/"+id+"/messages"; }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) { return (Map<String,Object>)value; }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> list(Object value) { return (List<Map<String,Object>>)value; }
    private static long n(Object value) { return ((Number)value).longValue(); }
    private static Set<String> recordIds(List<Map<String,Object>> records) { Set<String> ids=new HashSet<>(); for(Map<String,Object> record:records) ids.add(s(record,"id")); return ids; }
    private static List<Long> sequences(Object raw) { List<Long> result=new ArrayList<>(); for(Map<String,Object> message:list(raw)) result.add(n(message.get("sequence"))); return result; }
}
