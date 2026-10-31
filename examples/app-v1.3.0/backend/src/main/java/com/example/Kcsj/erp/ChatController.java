package com.example.Kcsj.erp;

import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import static com.example.Kcsj.erp.ErpStore.map;

@RestController
@RequestMapping("/chat")
public class ChatController {
    private final ChatService chat;
    public ChatController(ChatService chat) { this.chat = chat; }

    @GetMapping("/contacts")
    public Map<String, Object> contacts(HttpServletRequest request) {
        return map("code", 0, "data", chat.contacts(request));
    }

    @GetMapping("/conversations")
    public Map<String, Object> conversations(HttpServletRequest request) {
        return map("code", 0, "data", chat.conversations(request));
    }

    @PostMapping("/conversations")
    public Map<String, Object> open(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        return map("code", 0, "data", chat.open(request, body));
    }

    @GetMapping("/conversations/{id}/messages")
    public Map<String, Object> messages(HttpServletRequest request, @PathVariable String id,
            @RequestParam(required = false) String beforeSequence,
            @RequestParam(defaultValue = "50") String limit) {
        try {
            Long before = beforeSequence == null ? null : Long.valueOf(beforeSequence);
            return map("code", 0, "data", chat.messages(request, id, before, Integer.parseInt(limit)));
        } catch (NumberFormatException error) {
            throw new ApiException(400, "分页参数必须是有效整数");
        }
    }

    @PostMapping("/conversations/{id}/messages")
    public Map<String, Object> send(HttpServletRequest request, @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        return map("code", 0, "data", chat.send(request, id, body));
    }

    @PostMapping("/conversations/{id}/read")
    public Map<String, Object> read(HttpServletRequest request, @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        return map("code", 0, "data", chat.read(request, id, body));
    }

    @GetMapping("/conversations/{id}/export")
    public Map<String,Object> export(HttpServletRequest request,@PathVariable String id,
            @RequestParam(defaultValue="") String from,@RequestParam(defaultValue="") String through) {
        return map("code",0,"data",chat.export(request,id,from,through));
    }

    @GetMapping("/unread")
    public Map<String, Object> unread(HttpServletRequest request) {
        return map("code", 0, "data", chat.unread(request));
    }
}
