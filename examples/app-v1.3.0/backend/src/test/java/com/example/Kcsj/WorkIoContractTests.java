package com.example.Kcsj;

import com.example.Kcsj.erp.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static com.example.Kcsj.erp.ErpStore.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:work-io-contract;DB_CLOSE_DELAY=-1","erp.admin-password=","erp.upload-dir=target/work-io-uploads","erp.media.require-scan=false"})
@AutoConfigureMockMvc
class WorkIoContractTests {
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired ErpStore store; @Autowired AuthService auth; @Autowired JdbcTemplate jdbc;
    String teacher,student,other,admin;
    static final String TASK_HEADERS="任务标题,任务要求,接收人账号,截止时间,开始时间,需要交文件,需要审核\n";
    static final String BED_HEADERS="校区,楼栋,房间,床号,住宿条件,来源\n";
    @BeforeEach void setup(){
        jdbc.update("DELETE FROM erp_session");jdbc.update("DELETE FROM erp_record");
        teacher=user("c1","counselor","学院A","一班");student=user("s1","student","学院A","一班");
        other=user("c2","counselor","学院B","二班");admin=user("a1","admin","学院A","");user("s2","student","学院B","二班");
    }
    @Test void taskPreviewDoesNotPublishAndCommitRetriesDoNotDuplicate()throws Exception {
        Map<String,Object> batch=preview("tasks",TASK_HEADERS+"核对材料,请提交材料,s1,2099-12-01 17:00,,是,是\n",teacher);
        assertEquals(0,store.all("college_tasks").size());assertEquals(0L,n(batch.get("errorCount")));
        Map<String,Object> result=obj(call(post(commitPath(batch)),teacher,null,200));assertTrue(b(result,"committed"));
        call(post(commitPath(batch)),teacher,null,200);assertEquals(1,store.all("college_tasks").size());
        Map<String,Object> task=store.all("college_tasks").get(0);
        assertEquals("2099-12-01T09:00:00Z",s(task,"dueAt"));assertEquals("pending",s(task,"status"));assertTrue(b(task,"requiresFiles"));assertTrue(b(task,"needsReview"));assertEquals("s1",s(task,"assigneeId"));
    }
    @Test void erroneousFilesCannotPartiallyPublishAndErrorsAreActionable()throws Exception {
        Map<String,Object> batch=preview("tasks",TASK_HEADERS+"合法任务,核对,s1,2099-12-01 17:00,,否,是\n越权任务,核对,s2,2099-12-01 17:00,,否,是\n",teacher);
        assertEquals(1L,n(batch.get("errorCount")));call(post(commitPath(batch)),teacher,null,409);assertTrue(store.all("college_tasks").isEmpty());
        batch=preview("tasks",TASK_HEADERS+"日期错误,核对,s1,45231,,否,是\n",teacher);assertTrue(s(rows(batch.get("rows")).get(0),"error").contains("日期"));
        batch=preview("tasks",TASK_HEADERS+"错误开关,核对,s1,2099-12-01 17:00,,yes,是\n",teacher);assertEquals(1L,n(batch.get("errorCount")));
        batch=preview("tasks",TASK_HEADERS+"重复,核对,s1,2099-12-01 17:00,,否,是\n重复,核对,s1,2099-12-01 17:00,,否,是\n",teacher);assertEquals(1L,n(batch.get("errorCount")));
    }
    @Test void commitRechecksOwnershipPermissionsAndCurrentRecipientsAtomically()throws Exception {
        Map<String,Object> batch=preview("tasks",TASK_HEADERS+"给自己,核对,c1,2099-12-01 17:00,,否,是\n给学生,核对,s1,2099-12-01 17:00,,否,是\n",teacher);
        call(post(commitPath(batch)),other,null,403);
        Map<String,Object> account=store.get("user","s1");account.put("className","二班");store.save("user",account);
        call(post(commitPath(batch)),teacher,null,409);assertTrue(store.all("college_tasks").isEmpty());
        call(multipart("/college-work/bulk-import/tasks/preview").file(file(TASK_HEADERS+"不能发布,要求,c1,2099-12-01 17:00,,否,是\n")),student,null,403);
        assertTrue(rows(call(get("/college-work/bulk-import/tasks/recipients"),teacher,null,200)).stream().noneMatch(r->s(r,"account").equals("s2")));
    }
    @Test void bedPreviewRejectsDuplicatesAndCommitPreservesExistingOccupancy()throws Exception {
        Map<String,Object> batch=preview("beds",BED_HEADERS+"主校区,1,101,1,女生,台账\n主校区,1,101,1,女生,台账\n",teacher);
        assertEquals(1L,n(batch.get("errorCount")));assertTrue(store.all("erp_bed").isEmpty());
        batch=preview("beds",BED_HEADERS+"主校区,1,101,1,女生,台账\n",teacher);call(post(commitPath(batch)),teacher,null,200);call(post(commitPath(batch)),teacher,null,200);assertEquals(1,store.all("erp_bed").size());
        Map<String,Object> bed=store.all("erp_bed").get(0);bed.put("status","occupied");bed.put("studentId","s1");store.save("erp_bed",bed);
        batch=preview("beds","campus,building,room,bed,conditions,source\n主校区,1,101,1,女生,台账\n",teacher);
        assertEquals(1L,n(batch.get("errorCount")));call(post(commitPath(batch)),teacher,null,409);assertEquals("s1",s(store.get("erp_bed",s(bed,"id")),"studentId"));
    }
    @Test void expiredPreviewsAndChangedBedInventoryRequireRecheck()throws Exception {
        Map<String,Object> batch=preview("beds",BED_HEADERS+"主校区,1,101,1,女生,台账\n主校区,1,101,2,女生,台账\n",teacher);
        call(post("/college-work/beds"),teacher,map("campus","主校区","building","1","room","101","bed","2","conditions","女生","source","手工"),200);
        call(post(commitPath(batch)),teacher,null,409);assertEquals(1,store.all("erp_bed").size());
        Map<String,Object> stored=store.get("college_import",s(batch,"id"));stored.put("expiresAt","2020-01-01T00:00:00Z");store.save("college_import",stored);
        call(post(commitPath(batch)),teacher,null,409);
    }
    @Test void studentExportRechecksScopeAndNeverIncludesAdministrativeSecrets()throws Exception {
        store.save("student_profile",map("id","s1","studentNo","000123","college","学院A","phone","13800000000","email","demo@example.test","fieldSources",map("phone",map("internal","private"))));
        List<Map<String,Object>> own=rows(call(post("/students/export"),teacher,map("recordIds",Arrays.asList("s1")),200));assertEquals("000123",s(own.get(0),"studentNo"));assertFalse(own.get(0).containsKey("fieldSources"));
        Map<String,Object> adminRow=rows(call(post("/students/export"),admin,map("recordIds",Arrays.asList("s1")),200)).get(0);assertFalse(adminRow.containsKey("phone"));assertFalse(adminRow.containsKey("email"));
        call(post("/students/export"),teacher,map("recordIds",Arrays.asList("s1","s2")),403);
        call(post("/students/export"),student,map("recordIds",Arrays.asList("s2")),403);
        call(post("/students/export"),teacher,map("recordIds",Arrays.asList("s1","s1")),400);
        call(post("/students/export"),null,map("recordIds",Arrays.asList("s1")),401);
    }
    @Test void chatExportIncludesUnloadedHistoryAndUsesChinaDateBoundaries()throws Exception {
        String id=s(obj(call(post("/chat/conversations"),teacher,map("recipientId","s1"),200)),"id");
        for(int i=1;i<=55;i++)store.save("chat_message",map("id","export-"+i,"conversationId",id,"sequence",i,"senderId","c1","senderName","演示辅导员","content",i==55?"[表情包：收到]":"消息"+i,"stickerId",i==55?"received":"","attachmentIds",new ArrayList<>(),"createdAt",i==1?"2026-10-31T15:59:59Z":"2026-10-31T16:00:00Z"));
        Map<String,Object> conversation=store.get("chat_conversation",id);conversation.put("lastSequence",55);store.save("chat_conversation",conversation);
        String url="/chat/conversations/"+id+"/export";
        Map<String,Object> report=obj(call(get(url),teacher,null,200));assertEquals(55,rows(report.get("items")).size());
        report=obj(call(get(url).param("from","2026-11-01").param("through","2026-11-01"),student,null,200));assertEquals(54,rows(report.get("items")).size());assertEquals("[表情包：收到]",s(rows(report.get("items")).get(53),"content"));
        assertEquals(new HashSet<>(Arrays.asList("senderName","createdAt","content","attachments")),rows(report.get("items")).get(0).keySet());
        call(get(url).param("from","2026-11-02").param("through","2026-11-01"),teacher,null,400);call(get(url).param("from","2026-02-30"),teacher,null,400);
        call(get(url),admin,null,403);call(get(url),null,null,401);
        Map<String,Object> account=store.get("user","s1");account.put("className","二班");store.save("user",account);call(get(url),teacher,null,403);
    }
    @Test void housingExportsUseCurrentStudentScope()throws Exception {
        store.save("erp_housing",map("id","h1","studentId","s1","title","入住"));store.save("erp_housing",map("id","h2","studentId","s2","title","异院入住"));
        assertEquals(1,rows(call(post("/college-work/export"),teacher,map("module","housing","recordIds",Arrays.asList("h1")),200)).size());
        call(post("/college-work/export"),teacher,map("module","housing","recordIds",Arrays.asList("h1","h2")),403);
    }
    @Test void textFilesRejectBinaryDataAndKeepDownloadsAsAttachments()throws Exception {
        for(String ext:Arrays.asList("txt","csv")) {
            byte[] bytes="学号,姓名\r\n000123,演示学生\r\n".getBytes(StandardCharsets.UTF_8);
            MockMultipartFile upload=new MockMultipartFile("file","演示."+ext,"application/octet-stream",bytes);
            Map<String,Object> result=obj(call(multipart("/files").file(upload),teacher,null,200));
            Map<String,Object> stored=store.get("file",s(result,"id"));
            // Run the real processing checks synchronously in this isolated test.
            processing.process(stored,java.nio.file.Paths.get("target/work-io-uploads").resolve(s(stored,"storedName")));store.save("file",stored);
            assertEquals("ready",s(stored,"processingStatus"));
            org.springframework.mock.web.MockHttpServletResponse response=mvc.perform(get("/files/"+s(stored,"id")+"/download").header("Authorization","Bearer "+teacher)).andExpect(status().isOk()).andReturn().getResponse();
            assertArrayEquals(bytes,response.getContentAsByteArray());assertEquals("nosniff",response.getHeader("X-Content-Type-Options"));assertTrue(response.getHeader("Content-Disposition").startsWith("attachment;"));
            call(get("/files/"+s(stored,"id")+"/download"),other,null,403);
        }
        call(multipart("/files").file(new MockMultipartFile("file","binary.txt","text/plain",new byte[]{0,1,2,3})),teacher,null,400);
        call(multipart("/files").file(new MockMultipartFile("file","encoding.csv","text/csv",new byte[]{(byte)0xff,(byte)0xff})),teacher,null,400);
    }
    @Autowired MediaProcessingService processing;
    private String user(String id,String role,String college,String clazz){store.save("user",map("id",id,"username",id,"name",id,"role",role,"college",college,"className",clazz,"enabled",true,"mustChangePassword",false,"createdAt",now()));return s(auth.issueSession(store.get("user",id),"primary","test"),"token");}
    private MockMultipartFile file(String text){return new MockMultipartFile("file","导入.csv","text/csv",text.getBytes(StandardCharsets.UTF_8));}
    private Map<String,Object> preview(String module,String text,String token)throws Exception{return obj(call(multipart("/college-work/bulk-import/"+module+"/preview").file(file(text)),token,null,200));}
    private String commitPath(Map<String,Object> batch){return "/college-work/bulk-import/"+s(batch,"id")+"/commit";}
    private Object call(MockHttpServletRequestBuilder builder,String token,Map<String,Object> body,int expected)throws Exception{if(token!=null)builder.header("Authorization","Bearer "+token);if(body!=null)builder.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body));byte[] result=mvc.perform(builder).andExpect(status().is(expected)).andReturn().getResponse().getContentAsByteArray();return expected==200?json.readValue(result,Map.class).get("data"):null;}
    @SuppressWarnings("unchecked") private Map<String,Object> obj(Object value){return (Map<String,Object>)value;}
    @SuppressWarnings("unchecked") private List<Map<String,Object>> rows(Object value){return (List<Map<String,Object>>)value;}
    private long n(Object value){return ((Number)value).longValue();}
}
