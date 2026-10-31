package com.example.Kcsj.erp;

import java.time.*;
import java.util.*;
import javax.servlet.http.HttpServletRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import static com.example.Kcsj.erp.ErpStore.*;

/** Previewed, atomic imports reuse the same authorization and creation rules as forms. */
@RestController
@RequestMapping("/college-work/bulk-import")
public class WorkImportController {
    private final ErpStore store;
    private final AuthService auth;
    private final CollegeWorkController work;
    private final OperationsService op;
    public WorkImportController(ErpStore store, AuthService auth, CollegeWorkController work, OperationsService op) {
        this.store=store; this.auth=auth; this.work=work; this.op=op;
    }
    private Map<String,Object> actor(String module,HttpServletRequest request) {
        Map<String,Object> user=auth.require(request); auth.writeAllowed(user);
        if (module.equals("tasks")) op.allow(work.staff(user)||auth.hasRole(user,"admin"));
        else if (module.equals("beds")) {
            op.allow(auth.hasRole(user,"counselor","leader","logistics"));
            op.check(!s(user,"college").isEmpty(),"请先配置学院");
        } else throw new ApiException(400,"批量导入支持工作任务和宿舍床位；学生请使用学生名单导入");
        return user;
    }
    @GetMapping("/tasks/recipients")
    public Map<String,Object> recipients(HttpServletRequest request) {
        Map<String,Object> user=actor("tasks",request); List<Object> rows=new ArrayList<>();
        for (Map<String,Object> p:store.all("user")) if(auth.active(p)&&work.personScope(user,p))
            rows.add(map("account",s(p,"username"),"name",s(p,"name"),"className",s(p,"className")));
        return work.ok(rows);
    }
    @PostMapping("/{module}/preview") @Transactional
    public Map<String,Object> preview(@PathVariable String module,@RequestParam MultipartFile file,HttpServletRequest request) throws Exception {
        Map<String,Object> user=actor(module,request);
        List<Map<String,Object>> source=TabularImport.parse(file), rows=new ArrayList<>();
        op.check(source.size()<=500,"每次最多导入500行，请拆分文件");
        Set<String> seen=new HashSet<>(); int line=1;
        for(Map<String,Object> raw:source) {
            Map<String,Object> values=new LinkedHashMap<>(), row=map("row",++line,"values",values,"error","");
            try {
                String[][] columns=module.equals("tasks")?new String[][]{{"title","任务标题"},{"content","任务要求"},{"account","接收人账号"},{"dueAt","截止时间"},{"startsAt","开始时间"},{"requiresFiles","需要交文件"},{"needsReview","需要审核"}}:new String[][]{{"campus","校区"},{"building","楼栋"},{"room","房间"},{"bed","床号"},{"conditions","住宿条件"},{"source","来源"}};
                Set<String> known=new HashSet<>(); for(String[] column:columns) {known.add(column[0]);known.add(column[1]);}
                op.check(known.containsAll(raw.keySet()),"存在未知列，请使用下载的模板（保留必填列）");
                for(String[] column:columns) {
                    op.check(!(raw.containsKey(column[0])&&raw.containsKey(column[1])),"同一字段不能同时使用中英文列名："+column[1]);
                    values.put(column[0],s(raw,raw.containsKey(column[1])?column[1]:column[0]).trim());
                }
                normalize(module,values,user);
                String fingerprint=module.equals("beds")?bedKey(values):values.toString();
                op.check(seen.add(fingerprint),"文件内存在重复记录，请删除重复行");
            } catch(ApiException e) {row.put("error",e.getMessage());}
            rows.add(row);
        }
        Map<String,Object> batch=map("id",id(),"module",module,"ownerId",s(user,"id"),"permissionStamp",auth.permissionStamp(user),"rows",rows,"expiresAt",Instant.now().plusSeconds(3600).toString(),"committed",false,"createdAt",now());
        store.save("college_import",batch); auth.audit(user,"college.import_preview",module+"，行数 "+rows.size());
        return work.ok(view(batch));
    }
    @PostMapping("/{id}/commit") @Transactional
    public Map<String,Object> commit(@PathVariable String id,HttpServletRequest request) {
        store.lockWrites(); Map<String,Object> batch=store.get("college_import",id), user=actor(s(batch,"module"),request);
        op.allow(s(batch,"ownerId").equals(s(user,"id"))&&s(batch,"permissionStamp").equals(auth.permissionStamp(user)));
        if(b(batch,"committed"))return work.ok(view(batch));
        if(auth.expired(s(batch,"expiresAt")))throw new ApiException(409,"检查结果已过期，请重新检查文件");
        List<Map<String,Object>> rows=op.rows(batch,"rows");
        op.check(rows.stream().allMatch(r->s(r,"error").isEmpty()),"仍有错误行，请修正文件并重新检查");
        String module=s(batch,"module");
        // Validate the entire batch again under the write lock before any creation.
        for(Map<String,Object> row:rows) normalize(module,AccountGovernanceController.object(row,"values"),user);
        for(Map<String,Object> row:rows) {
            Map<String,Object> values=AccountGovernanceController.object(row,"values");
            if(module.equals("tasks")) {
                normalize(module,values,user);
                values.put("clientId","import-"+id+"-"+row.get("row"));
                values.put("assigneeIds",Arrays.asList(s(values,"assigneeId")));
                values.put("requiresFiles",s(values,"requiresFiles").equals("是"));
                values.put("needsReview",s(values,"needsReview").equals("是"));
                work.create("tasks",values,request);
            } else work.bed(values,request);
        }
        batch.put("committed",true);batch.put("committedAt",now());store.save("college_import",batch);
        auth.audit(user,"college.import_commit",module+"，行数 "+rows.size());return work.ok(view(batch));
    }
    private void normalize(String module,Map<String,Object> values,Map<String,Object> user) {
        if(module.equals("tasks")) {
            for(String key:Arrays.asList("title","content","account"))op.text(values,key,true);
            Map<String,Object> person=null;
            for(Map<String,Object> candidate:store.all("user"))if(s(candidate,"username").equals(s(values,"account")))person=candidate;
            op.check(person!=null&&auth.active(person)&&work.personScope(user,person),"接收人账号无效、未启用或不在授权范围，请核对接收人清单");
            op.check(s(values,"assigneeId").isEmpty()||s(values,"assigneeId").equals(s(person,"id")),"接收人账号发生变化，请重新检查");
            values.put("assigneeId",s(person,"id"));values.put("assigneeName",s(person,"name"));
            for(String key:Arrays.asList("dueAt","startsAt")) {
                String value=s(values,key).replace(' ','T');
                if(value.matches("\\d+(\\.\\d+)?"))throw new ApiException(400,"日期请填文本，例如 2026-12-01 17:00（北京时间），不要使用Excel日期序号");
                values.put(key,op.date(map(key,value),key,key.equals("dueAt")));
            }
            Instant start=s(values,"startsAt").isEmpty()?Instant.now():Instant.parse(s(values,"startsAt"));
            op.check(Instant.parse(s(values,"dueAt")).isAfter(start)&&Instant.parse(s(values,"dueAt")).isAfter(Instant.now()),"截止时间须晚于当前时间和开始时间");
            for(String key:Arrays.asList("requiresFiles","needsReview")) {
                String value=s(values,key);if(value.isEmpty())value=key.equals("needsReview")?"是":"否";
                op.check(Arrays.asList("是","否").contains(value),"需要交文件、需要审核请填写是或否");values.put(key,value);
            }
        } else {
            for(String key:Arrays.asList("campus","building","room","bed","conditions","source"))op.text(values,key,true);
            for(Map<String,Object> existing:store.all("erp_bed"))op.check(!bedKey(existing).equals(bedKey(values)),"该床位已经存在，请核对校区、楼栋、房间和床号");
        }
    }
    private String bedKey(Map<String,Object> row) { return Arrays.asList(s(row,"campus"),s(row,"building"),s(row,"room"),s(row,"bed")).toString(); }
    private Map<String,Object> view(Map<String,Object> batch) {
        List<Map<String,Object>> rows=op.rows(batch,"rows");long errors=rows.stream().filter(r->!s(r,"error").isEmpty()).count();
        return map("id",s(batch,"id"),"module",s(batch,"module"),"rows",rows,"count",rows.size(),"errorCount",errors,"validCount",rows.size()-errors,"committed",b(batch,"committed"));
    }
}
