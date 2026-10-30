package com.example.Kcsj.erp;

import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import java.util.*;
import static com.example.Kcsj.erp.ErpStore.*;

/** HTTP facade for the counselor AI helper. Generated text is never published automatically. */
@RestController
@RequestMapping("/ai")
public class AiAssistantController {
    private final AuthService auth;
    private final AiAssistantService assistant;
    public AiAssistantController(AuthService auth, AiAssistantService assistant) { this.auth = auth; this.assistant = assistant; }

    @GetMapping("/status")
    public Map<String,Object> status(HttpServletRequest request) {
        Map<String,Object> actor = auth.require(request);
        auth.roles(actor, "admin", "counselor", "mentor", "leader", "business");
        return ok(assistant.status());
    }

    @PostMapping("/assist")
    public Map<String,Object> assist(@RequestBody(required=false) Map<String,Object> body, HttpServletRequest request) {
        Map<String,Object> actor = auth.require(request);
        auth.writeAllowed(actor);
        auth.roles(actor, "admin", "counselor", "mentor", "leader", "business");
        if (body == null) body = Collections.emptyMap();
        Map<String,Object> result = new LinkedHashMap<>(assistant.assist(body));
        result.put("requestId", id());
        auth.audit(actor, "ai.assist", String.valueOf(result.get("mode")) + ":" + String.valueOf(body.getOrDefault("action", body.getOrDefault("scenario", "chat"))));
        return ok(result);
    }

    @PutMapping("/config")
    public Map<String,Object> configure(@RequestBody(required=false) Map<String,Object> body,HttpServletRequest request){
        Map<String,Object> actor=auth.require(request);auth.admin(actor);
        Map<String,Object> input=body==null?Collections.emptyMap():body;
        Map<String,Object> status=assistant.configure(input);auth.audit(actor,"ai.config","更新AI服务配置（密钥不写入审计）");
        return ok(status);
    }

    @GetMapping("/config")
    public Map<String,Object> configuration(HttpServletRequest request){
        auth.admin(auth.require(request));return ok(assistant.adminConfiguration());
    }

    /** Compatibility shortcut for clients that only expose a notice drafting action. */
    @PostMapping("/notice-draft")
    public Map<String,Object> noticeDraft(@RequestBody(required=false) Map<String,Object> body, HttpServletRequest request) {
        Map<String,Object> input = body == null ? new LinkedHashMap<>() : new LinkedHashMap<>(body);
        // Older clients used `action` for the notice body. Preserve it when they
        // have not migrated to the unified `details` field yet.
        if (input.get("details") == null && input.get("content") == null && input.get("description") == null
                && input.get("action") != null) {
            input.put("details", input.get("action"));
        }
        input.put("action", "notice_draft");
        return assist(input, request);
    }

    private Map<String,Object> ok(Object data) { return map("code", 0, "data", data); }
}
