package com.example.Kcsj.erp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.annotation.PostConstruct;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import static com.example.Kcsj.erp.ErpStore.*;

@Service
public class AuthService {
    private final ErpStore store;
    private final SecureRandom random = new SecureRandom();
    private final Map<String,long[]> attempts = new HashMap<>();
    @Value("${erp.admin-password:}") private String initialPassword;
    @Value("${erp.restore.archive:}") private String restoreArchive;
    public AuthService(ErpStore store) { this.store=store; }
    @PostConstruct
    public void bootstrap() {
        if(restoreArchive!=null&&!restoreArchive.isEmpty())return;
        if(store.all("user").isEmpty() && initialPassword.isEmpty()) {
            org.slf4j.LoggerFactory.getLogger(AuthService.class).warn("No ERP administrator exists. Set ERP_ADMIN_PASSWORD (12+ characters, letters and digits) and restart to initialize admin. No default password is enabled.");
        }
        if (store.all("user").isEmpty() && !initialPassword.isEmpty()) {
            passwordPolicy(initialPassword);
            Map<String,Object> admin=map("id",id(),"username","admin","name","超级管理员","role","admin","className","","enabled",true,"theme","blue","mustChangePassword",true,"createdAt",now(),"passwordHash",hashPassword(initialPassword));
            store.save("user",admin);
            audit(admin,"account.bootstrap","初始化超级管理员；首次登录需修改密码");
        }
    }
    @Transactional(noRollbackFor=ApiException.class)
    public synchronized Map<String,Object> login(String username,String password,String remote) {
        store.lockWrites();
        long current=System.currentTimeMillis();
        String key="account:"+username.toLowerCase(Locale.ROOT),source="source:"+remote;
        attempts.entrySet().removeIf(e -> current-e.getValue()[0]>=900000);
        // Independent limits prevent rotating source addresses or account names from bypassing protection.
        if(limited(key,5)||limited(source,100)||attempts.size()>9998)
            throw new ApiException(429,"尝试次数过多，请15分钟后重试");
        attempts.computeIfAbsent(key,k->new long[]{current,0})[1]++;
        attempts.computeIfAbsent(source,k->new long[]{current,0})[1]++;
        Map<String,Object> user=null;
        for(Map<String,Object> item:store.all("user")) if(s(item,"username").equalsIgnoreCase(username)) user=item;
        if(user==null || !verify(password,s(user,"passwordHash"))){audit(map("id","","name","未认证访问"),"auth.failed","登录校验失败；来源 "+remote);throw new ApiException(401,"账号或密码错误");}
        if(!active(user)){audit(user,"auth.failed","账号状态拒绝登录；来源 "+remote);throw new ApiException(401,expired(s(user,"validUntil"))?"账号已过期，请联系管理员处理":"账号未激活、已锁定或尚未生效，请联系管理员处理");}
        if(b(user,"mustChangePassword") && expired(s(user,"temporaryPasswordExpiresAt"))){audit(user,"auth.temporary_expired","临时密码过期；来源 "+remote);throw new ApiException(401,"临时密码已过期，请联系管理员重新发放");}
        attempts.remove(key);
        attempts.get(source)[1]--;
        audit(user,"auth.login","账号登录");
        return issueSession(user,"primary",remote);
    }
    private boolean limited(String key,long maximum){long[] entry=attempts.get(key);return entry!=null&&entry[1]>=maximum;}
    public Map<String,Object> require(HttpServletRequest request) {
        if ((request.getMethod().equals("POST") || request.getMethod().equals("PUT")) && org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) store.lockWrites();
        String header=request.getHeader("Authorization");
        if(header==null || !header.startsWith("Bearer ")) throw new ApiException(401,"请先登录");
        String sessionHash=digest(header.substring(7));
        Map<String,Object> user=store.get("user",store.sessionUser(sessionHash));
        if(!active(user)) throw new ApiException(401,"账号已停用、锁定或超出有效期");
        if(b(user,"mustChangePassword") && expired(s(user,"temporaryPasswordExpiresAt"))) throw new ApiException(401,"临时密码已过期");
        String path=request.getRequestURI().substring(request.getContextPath().length());
        if(b(user,"mustChangePassword")&&!Arrays.asList("/auth/me","/auth/password","/auth/logout").contains(path))throw new ApiException(403,"PASSWORD_CHANGE_REQUIRED","首次登录或密码重置后，请先修改密码再查看业务资料");
        String roleId="primary";
        try{String selected=s(store.get("login_device",sessionHash),"roleAssignmentId");if(!selected.isEmpty())roleId=selected;}catch(ApiException missing){if(missing.status!=404)throw missing;}
        Map<String,Object> actor=roleContext(user,roleId);
        if(b(actor,"roleRestricted")&&!allowedRoleRequest(actor,path,request.getMethod()))throw new ApiException(403,"ROLE_MODULE_RESTRICTED","当前附加角色仅支持已明确授权的学院业务模块；请切回主身份办理其他事项");
        return actor;
    }
    /** Role selection is a server-side session property; caller supplied role fields are never trusted. */
    public Map<String,Object> issueSession(Map<String,Object> user,String roleId,String remote){
        Map<String,Object> actor=roleContext(user,roleId);byte[] bytes=new byte[32];random.nextBytes(bytes);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);long expires=System.currentTimeMillis()+12L*60*60*1000;
        store.session(digest(token),s(user,"id"),expires);store.save("login_device",map("id",digest(token),"userId",s(user,"id"),"roleAssignmentId",roleId,"role",s(actor,"role"),"remote",remote,"createdAt",now(),"expiresAt",java.time.Instant.ofEpochMilli(expires).toString(),"revoked",false));return map("token",token,"user",publicUser(actor));
    }
    public boolean primaryContext(Map<String,Object> actor){return s(actor,"activeRoleId").isEmpty()||s(actor,"activeRoleId").equals("primary");}
    public List<String> roleModules(String role){
        if(role.equals("business"))return Arrays.asList("awards","enrollment");
        if(role.equals("leader"))return Arrays.asList("awards","enrollment");
        if(role.equals("finance"))return Arrays.asList("awards","receipts");
        if(role.equals("logistics"))return Arrays.asList("beds","housing");
        return Collections.emptyList();
    }
    public boolean assignmentActive(Map<String,Object> assignment,Map<String,Object> user){return s(assignment,"userId").equals(s(user,"id"))&&s(assignment,"status").equals("approved")&&s(assignment,"primaryRole").equals(s(user,"role"))&&!s(user,"role").equals("student")&&!roleModules(s(assignment,"role")).isEmpty()&&!expired(s(assignment,"until"))&&!s(assignment,"from").isEmpty()&&!java.time.Instant.parse(s(assignment,"from")).isAfter(java.time.Instant.now());}
    public Map<String,Object> roleContext(Map<String,Object> user,String roleId){
        Map<String,Object> actor=new LinkedHashMap<>(user);actor.put("primaryRole",s(user,"role"));actor.put("activeRoleId",roleId);
        actor.put("roleRestricted",!roleId.equals("primary"));
        if(!roleId.equals("primary")){Map<String,Object> assignment;try{assignment=store.get("role_assignment",roleId);}catch(ApiException e){throw new ApiException(401,"附加角色已失效，请重新登录");}if(!assignmentActive(assignment,user))throw new ApiException(401,"附加角色已撤销、过期或身份发生变化，请重新登录");actor.put("role",s(assignment,"role"));actor.put("className",s(assignment,"className"));actor.put("college",s(assignment,"college"));actor.put("organizationId","");actor.put("allowedModules",roleModules(s(assignment,"role")));}
        else actor.put("allowedModules",Collections.emptyList());return actor;
    }
    private boolean allowedRoleRequest(Map<String,Object> actor,String path,String method){
        if(Arrays.asList("/auth/me","/auth/roles","/auth/switch-role","/auth/password","/auth/theme","/auth/logout").contains(path))return true;
        if(path.equals("/identity/role-students")&&method.equals("GET")&&hasRole(actor,"business","leader","logistics"))return true;
        if(path.equals("/knowledge")&&method.equals("GET"))return true;
        for(String module:roleModules(s(actor,"role")))if(path.equals("/erp/"+module)||path.matches("/erp/"+module+"/[A-Za-z0-9_-]{1,80}(/action)?"))return true;
        return false;
    }
    public void writeAllowed(Map<String,Object> user) {
        if(b(user,"mustChangePassword")) throw new ApiException(403,"PASSWORD_CHANGE_REQUIRED","首次登录或密码重置后，请先修改密码");
    }
    public void admin(Map<String,Object> user) { if(!s(user,"role").equals("admin")||s(user,"adminLevel").equals("administrator")) throw new ApiException(403,"仅超级管理员可执行此操作"); }
    public void staff(Map<String,Object> user) { roles(user,"admin","counselor"); }
    public boolean hasRole(Map<String,Object> user,String... roles) { return Arrays.asList(roles).contains(s(user,"role")); }
    public void roles(Map<String,Object> user,String... roles) { if(!hasRole(user,roles))throw new ApiException(403,"当前角色无权执行此操作"); }
    public boolean expired(String value){return !value.isEmpty() && !java.time.Instant.parse(value).isAfter(java.time.Instant.now());}
    public boolean active(Map<String,Object> user){return b(user,"enabled")&&!b(user,"locked")&&!expired(s(user,"validUntil"))&&(s(user,"validFrom").isEmpty()||!java.time.Instant.parse(s(user,"validFrom")).isAfter(java.time.Instant.now()));}
    /** Business scope deliberately excludes administrator privilege. */
    public boolean inScope(Map<String,Object> actor,Map<String,Object> student){
        if(s(actor,"id").equals(s(student,"id")))return true;
        if(!hasRole(actor,"counselor")||!hasRole(student,"student"))return false;
        if(classes(actor).contains(s(student,"className")))return true;
        if(primaryContext(actor))for(Map<String,Object> d:store.all("scope_delegation"))if(s(d,"assigneeId").equals(s(actor,"id"))&&!b(d,"revoked")&&!expired(s(d,"until"))&&!java.time.Instant.parse(s(d,"from")).isAfter(java.time.Instant.now())&&Arrays.asList(s(d,"className").split(",")).contains(s(student,"className")))return true;
        return false;
    }
    @SuppressWarnings("unchecked") public boolean granted(Map<String,Object> actor,String permission,String studentId){
        if(!primaryContext(actor))return false;
        for(Map<String,Object> g:store.all("permission_grant"))if(s(g,"assigneeId").equals(s(actor,"id"))&&s(g,"status").equals("approved")&&s(g,"permission").equals(permission)&&!expired(s(g,"until"))&&!java.time.Instant.parse(s(g,"from")).isAfter(java.time.Instant.now())&&(((List<String>)g.get("studentIds")).contains(studentId)||(studentId.isEmpty()&&b(g,"global")&&Arrays.asList("notice.emergency","office.manage").contains(permission))))return true;
        return false;
    }
    public boolean businessAccess(Map<String,Object> actor,String permission,Map<String,Object> student){return inScope(actor,student)||granted(actor,permission,s(student,"id"));}
    /** Include temporal grant state when deriving any authorization-sensitive cache key. */
    public String permissionStamp(Map<String,Object>actor){StringBuilder key=new StringBuilder(primaryContext(actor)?"primary":s(actor,"activeRoleId")).append('|').append(s(actor,"role")).append('|').append(s(actor,"className")).append('|').append(s(actor,"college")).append('|').append(active(actor));for(String kind:Arrays.asList("permission_grant","scope_delegation"))for(Map<String,Object>g:store.all(kind))if(s(g,"assigneeId").equals(s(actor,"id")))key.append('|').append(g.toString()).append('|').append(expired(s(g,"until"))).append('|').append(!s(g,"from").isEmpty()&&java.time.Instant.parse(s(g,"from")).isAfter(java.time.Instant.now()));return digest(key.toString());}
    public boolean manages(Map<String,Object> actor,Map<String,Object> target) {
        return s(actor,"role").equals("admin") || inScope(actor,target);
    }
    public Set<String> classes(Map<String,Object> user) {
        Set<String> names=new LinkedHashSet<>();
        for(String value:s(user,"className").split("[,，]")) if(!value.trim().isEmpty()) names.add(value.trim());
        return names;
    }
    public Map<String,Object> publicUser(Map<String,Object> user) {
        Map<String,Object> result=new LinkedHashMap<>(user);result.remove("passwordHash");result.put("assignedClasses",new ArrayList<>(classes(user)));return result;
    }
    public void audit(Map<String,Object> actor,String action,String detail) {
        store.save("audit",map("id",id(),"time",now(),"action",action,"operator",s(actor,"name"),"operatorId",s(actor,"id"),"detail",detail));
    }
    public void passwordPolicy(String password) {
        if(password.length()<12 || password.length()>128 || !password.matches(".*[A-Za-z].*") || !password.matches(".*[0-9].*")) throw new ApiException(400,"密码须为12至128位，且包含字母和数字");
    }
    public String hashPassword(String password) {
        byte[] salt=new byte[16];random.nextBytes(salt);
        return "pbkdf2$120000$"+Base64.getEncoder().encodeToString(salt)+"$"+Base64.getEncoder().encodeToString(pbkdf2(password,salt,120000));
    }
    public boolean verify(String password,String encoded) {
        try {String[] parts=encoded.split("\\$");return parts.length==4 && MessageDigest.isEqual(Base64.getDecoder().decode(parts[3]),pbkdf2(password,Base64.getDecoder().decode(parts[2]),Integer.parseInt(parts[1])));} catch(Exception e){return false;}
    }
    private byte[] pbkdf2(String password,byte[] salt,int rounds) {
        PBEKeySpec spec=new PBEKeySpec(password.toCharArray(),salt,rounds,256);
        try{return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();}catch(Exception e){throw new IllegalStateException(e);}finally{spec.clearPassword();}
    }
    public String digest(String text) {
        try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format("%02x",b));return out.toString();}catch(Exception e){throw new IllegalStateException(e);}
    }
}
