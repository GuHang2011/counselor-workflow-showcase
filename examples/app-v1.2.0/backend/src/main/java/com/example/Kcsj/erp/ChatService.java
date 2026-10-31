package com.example.Kcsj.erp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.example.Kcsj.erp.ErpStore.*;

@Service
public class ChatService implements AttachmentAccess {
    private static final String CONVERSATION = "chat_conversation";
    private static final String MESSAGE = "chat_message";
    private static final Set<String> STAFF = new HashSet<>(Arrays.asList(
            "admin", "counselor", "leader", "business", "mentor", "finance",
            "logistics", "sysadmin", "assistant"));
    private final ErpStore store;
    private final AuthService auth;

    public ChatService(ErpStore store, AuthService auth) {
        this.store = store;
        this.auth = auth;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> contacts(HttpServletRequest request) {
        Map<String, Object> actor = actor(request);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> user : store.all("user")) {
            if (canContact(actor, user)) result.add(contact(user));
        }
        result.sort(Comparator.comparing(user -> s(user, "name")));
        return result;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> conversations(HttpServletRequest request) {
        Map<String, Object> actor = actor(request);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> conversation : store.all(CONVERSATION)) {
            if (!participants(conversation).contains(s(actor, "id"))) continue;
            Map<String, Object> peer = existingPeer(conversation, actor);
            if (peer != null && canContact(actor, peer)) result.add(summary(conversation, actor, peer));
        }
        result.sort((left, right) -> {
            int recent = lastCreatedAt(right).compareTo(lastCreatedAt(left));
            return recent != 0 ? recent : s(left, "id").compareTo(s(right, "id"));
        });
        return result;
    }

    @Transactional
    public Map<String, Object> open(HttpServletRequest request, Map<String, Object> body) {
        Map<String, Object> actor = actor(request);
        store.lockWrites();
        actor = currentActor(actor);
        String recipientId = requiredText(body, "recipientId", 80);
        Map<String, Object> peer;
        try { peer = store.get("user", recipientId); }
        catch (ApiException missing) { throw new ApiException(403, "不允许联系该用户"); }
        if (!canContact(actor, peer)) throw new ApiException(403, "不允许联系该用户");
        String actorId = s(actor, "id");
        List<String> pair = new ArrayList<>(Arrays.asList(actorId, recipientId));
        pair.sort(String::compareTo);
        String conversationId = "chat-" + auth.digest(pair.get(0).length() + ":" + pair.get(0) + ":" + pair.get(1));
        Map<String, Object> conversation = find(CONVERSATION, conversationId);
        if (conversation == null) {
            Map<String, Object> readSequences = map(actorId, 0L, recipientId, 0L);
            conversation = map("id", conversationId, "participantIds", pair,
                    "readSequences", readSequences, "lastSequence", 0L,
                    "createdAt", now(), "lastMessageId", "");
            store.save(CONVERSATION, conversation);
        }
        return summary(conversation, actor, peer);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> messages(HttpServletRequest request, String conversationId,
            Long beforeSequence, int limit) {
        Map<String, Object> actor = actor(request);
        Map<String, Object> conversation = authorized(conversationId, actor);
        long snapshotSequence = number(conversation.get("lastSequence"));
        if (limit < 1 || limit > 100) throw new ApiException(400, "每页消息数量应为1至100");
        if (beforeSequence != null && beforeSequence <= 0)
            throw new ApiException(400, "历史消息游标必须为正整数");
        List<Map<String, Object>> candidates = conversationMessages(conversationId);
        candidates.removeIf(message -> sequence(message) > snapshotSequence);
        if (beforeSequence != null) candidates.removeIf(message -> sequence(message) >= beforeSequence);
        boolean hasMore = candidates.size() > limit;
        if (hasMore) candidates = new ArrayList<>(candidates.subList(candidates.size() - limit, candidates.size()));
        Long nextBefore = hasMore && !candidates.isEmpty() ? sequence(candidates.get(0)) : null;
        return map("items", candidates, "hasMore", hasMore, "nextBefore", nextBefore,
                "latestSequence", snapshotSequence);
    }

    @Transactional
    public Map<String, Object> send(HttpServletRequest request, String conversationId,
            Map<String, Object> body) {
        Map<String, Object> actor = actor(request);
        store.lockWrites();
        actor = currentActor(actor);
        Map<String, Object> conversation = authorized(conversationId, actor);
        String clientMessageId = requiredText(body, "clientMessageId", 128);
        Object rawContent = body.get("content");
        if (rawContent != null && !(rawContent instanceof String))
            throw new ApiException(400, "消息内容必须为文本");
        String content = rawContent == null ? "" : ((String) rawContent).trim();
        if (content.codePointCount(0, content.length()) > 2000)
            throw new ApiException(400, "消息不能超过2000字");
        List<String> attachmentIds = attachments(body.get("attachmentIds"));
        String stickerId = ChatStickerCatalog.requireId(body.get("stickerId"));
        // Keep a readable description for older clients and conversation summaries.
        if (content.isEmpty() && !stickerId.isEmpty()) content = ChatStickerCatalog.fallback(stickerId);
        if (content.isEmpty() && attachmentIds.isEmpty()) throw new ApiException(400, "消息不能为空");
        for (Map<String, Object> previous : conversationMessages(conversationId)) {
            if (s(previous, "senderId").equals(s(actor, "id"))
                    && s(previous, "clientMessageId").equals(clientMessageId)) {
                if (!content.equals(s(previous, "content"))
                        || !attachmentIds.equals(strings(previous.get("attachmentIds")))
                        || !stickerId.equals(s(previous, "stickerId")))
                    throw new ApiException(409, "该消息标识已用于不同内容");
                return previous;
            }
        }
        for (String fileId : attachmentIds) validateAttachment(actor, fileId);
        long nextSequence = number(conversation.get("lastSequence")) + 1;
        if (nextSequence <= 0) throw new ApiException(409, "会话消息序号已达到上限");
        Map<String, Object> message = map("id", "cm-" + id(), "conversationId", conversationId,
                "senderId", s(actor, "id"), "senderName", s(actor, "name"),
                "content", content, "attachmentIds", attachmentIds, "stickerId", stickerId, "createdAt", now(),
                "sequence", nextSequence, "clientMessageId", clientMessageId);
        store.save(MESSAGE, message);
        conversation.put("lastSequence", nextSequence);
        conversation.put("lastMessageId", s(message, "id"));
        store.save(CONVERSATION, conversation);
        return message;
    }

    @Transactional
    public Map<String, Object> read(HttpServletRequest request, String conversationId,
            Map<String, Object> body) {
        Map<String, Object> actor = actor(request);
        store.lockWrites();
        actor = currentActor(actor);
        Map<String, Object> conversation = authorized(conversationId, actor);
        Object rawSequence = body.get("throughSequence");
        long throughSequence;
        if (!(rawSequence instanceof Number)) throw new ApiException(400, "已读游标必须为整数");
        try { throughSequence = Long.parseLong(rawSequence.toString()); }
        catch (NumberFormatException invalid) { throw new ApiException(400, "已读游标必须为整数"); }
        long latest = number(conversation.get("lastSequence"));
        if (throughSequence < 0 || throughSequence > latest)
            throw new ApiException(400, "已读游标不能超过当前消息范围");
        Map<String, Object> reads = readSequences(conversation);
        long current = number(reads.get(s(actor, "id")));
        long updated = Math.max(current, throughSequence);
        if (updated != current) {
            reads.put(s(actor, "id"), updated);
            conversation.put("readSequences", reads);
            store.save(CONVERSATION, conversation);
        }
        return map("readSequence", updated);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> unread(HttpServletRequest request) {
        Map<String, Object> actor = actor(request);
        long unread = 0;
        for (Map<String, Object> conversation : store.all(CONVERSATION)) {
            if (!participants(conversation).contains(s(actor, "id"))) continue;
            Map<String, Object> peer = existingPeer(conversation, actor);
            if (peer != null && canContact(actor, peer)) unread += unreadCount(conversation, actor);
        }
        return map("unreadCount", unread);
    }

    @Override
    public boolean canRead(Map<String, Object> actor, String fileId) {
        if (b(actor, "roleRestricted") || !auth.primaryContext(actor) || !auth.active(actor)) return false;
        Map<String, Object> file = find("file", fileId);
        if (file == null || !"ready".equals(s(file, "processingStatus"))
                || auth.expired(s(file, "expiresAt"))) return false;
        for (Map<String, Object> message : store.all(MESSAGE)) {
            if (!strings(message.get("attachmentIds")).contains(fileId)) continue;
            Map<String, Object> conversation = find(CONVERSATION, s(message, "conversationId"));
            if (conversation == null || !participants(conversation).contains(s(actor, "id"))) continue;
            Map<String, Object> peer = existingPeer(conversation, actor);
            if (peer != null && canContact(actor, peer)) return true;
        }
        return false;
    }

    private Map<String, Object> actor(HttpServletRequest request) {
        Map<String, Object> actor = auth.require(request);
        if (b(actor, "roleRestricted") || !auth.primaryContext(actor))
            throw new ApiException(403, "请切换至主身份后使用内部聊天");
        return actor;
    }

    private Map<String, Object> currentActor(Map<String, Object> actor) {
        Map<String, Object> current = store.get("user", s(actor, "id"));
        if (!auth.active(current)) throw new ApiException(403, "当前账号已停用");
        auth.writeAllowed(current);
        return current;
    }

    private boolean canContact(Map<String, Object> actor, Map<String, Object> peer) {
        if (s(actor, "id").equals(s(peer, "id")) || !auth.active(actor) || !auth.active(peer)
                || b(actor, "mustChangePassword") || b(peer, "mustChangePassword")) return false;
        String actorRole = s(actor, "role"), peerRole = s(peer, "role");
        if ("student".equals(actorRole))
            return "counselor".equals(peerRole) && auth.inScope(peer, actor);
        if ("student".equals(peerRole))
            return "counselor".equals(actorRole) && auth.inScope(actor, peer);
        if (!STAFF.contains(actorRole) || !STAFF.contains(peerRole)) return false;
        if ("admin".equals(actorRole) || "admin".equals(peerRole)) return true;
        String college = s(actor, "college").trim();
        return !college.isEmpty() && college.equals(s(peer, "college").trim());
    }

    private Map<String, Object> authorized(String conversationId, Map<String, Object> actor) {
        Map<String, Object> conversation = store.get(CONVERSATION, conversationId);
        if (!participants(conversation).contains(s(actor, "id")))
            throw new ApiException(403, "只有会话参与者可以访问消息");
        Map<String, Object> peer = existingPeer(conversation, actor);
        if (peer == null || !canContact(actor, peer))
            throw new ApiException(403, "当前双方已不在允许联系的范围内");
        return conversation;
    }

    private Map<String, Object> existingPeer(Map<String, Object> conversation, Map<String, Object> actor) {
        List<String> pair = participants(conversation);
        if (pair.size() != 2 || !pair.contains(s(actor, "id"))) return null;
        String peerId = pair.get(0).equals(s(actor, "id")) ? pair.get(1) : pair.get(0);
        return find("user", peerId);
    }

    private Map<String, Object> summary(Map<String, Object> conversation,
            Map<String, Object> actor, Map<String, Object> peer) {
        Map<String, Object> reads = readSequences(conversation);
        Map<String, Object> last = s(conversation, "lastMessageId").isEmpty()
                ? null : find(MESSAGE, s(conversation, "lastMessageId"));
        return map("id", s(conversation, "id"), "peer", contact(peer),
                "unreadCount", unreadCount(conversation, actor), "lastMessage", last,
                "lastSequence", number(conversation.get("lastSequence")),
                "readSequence", number(reads.get(s(actor, "id"))),
                "peerReadSequence", number(reads.get(s(peer, "id"))), "canSend", true);
    }

    private long unreadCount(Map<String, Object> conversation, Map<String, Object> actor) {
        long read = number(readSequences(conversation).get(s(actor, "id")));
        long latest = number(conversation.get("lastSequence"));
        long unread = 0;
        for (Map<String, Object> message : conversationMessages(s(conversation, "id"))) {
            if (sequence(message) > read && sequence(message) <= latest
                    && !s(message, "senderId").equals(s(actor, "id"))) unread++;
        }
        return unread;
    }

    private Map<String, Object> contact(Map<String, Object> user) {
        return map("id", s(user, "id"), "name", s(user, "name"),
                "role", s(user, "role"), "className", s(user, "className"), "college", s(user, "college"));
    }

    private List<Map<String, Object>> conversationMessages(String conversationId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> message : store.all(MESSAGE)) {
            if (conversationId.equals(s(message, "conversationId"))) result.add(message);
        }
        result.sort(Comparator.comparingLong(ChatService::sequence));
        return result;
    }

    private void validateAttachment(Map<String, Object> actor, String fileId) {
        Map<String, Object> file = find("file", fileId);
        if (file == null) throw new ApiException(400, "附件不存在");
        if (!s(actor, "id").equals(s(file, "ownerId")))
            throw new ApiException(403, "只能发送本人上传的文件");
        if (!"ready".equals(s(file, "processingStatus")))
            throw new ApiException(400, "附件尚未完成安全检查");
        if (auth.expired(s(file, "expiresAt"))) throw new ApiException(410, "附件已过期");
    }

    private List<String> attachments(Object value) {
        if (value == null) return new ArrayList<>();
        if (!(value instanceof List)) throw new ApiException(400, "附件列表格式错误");
        List<?> raw = (List<?>) value;
        if (raw.size() > 10) throw new ApiException(400, "每条消息最多发送10个文件");
        List<String> result = new ArrayList<>();
        for (Object item : raw) {
            if (!(item instanceof String)) throw new ApiException(400, "附件标识格式错误");
            String fileId = ((String) item).trim();
            if (fileId.isEmpty() || fileId.length() > 80 || result.contains(fileId))
                throw new ApiException(400, "附件标识为空、过长或重复");
            result.add(fileId);
        }
        return result;
    }

    private static String requiredText(Map<String, Object> body, String key, int max) {
        Object value = body.get(key);
        if (!(value instanceof String)) throw new ApiException(400, "缺少有效的" + key);
        String text = ((String) value).trim();
        if (text.isEmpty() || text.length() > max) throw new ApiException(400, key + "长度不合法");
        return text;
    }

    private Map<String, Object> find(String kind, String recordId) {
        try { return store.get(kind, recordId); }
        catch (ApiException missing) {
            if (missing.status != 404) throw missing;
            return null;
        }
    }

    private static List<String> participants(Map<String, Object> conversation) {
        return strings(conversation.get("participantIds"));
    }

    private static List<String> strings(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List) {
            for (Object item : (List<?>) value) if (item instanceof String) result.add((String) item);
        }
        return result;
    }

    private static Map<String, Object> readSequences(Map<String, Object> conversation) {
        Map<String, Object> result = new LinkedHashMap<>();
        Object raw = conversation.get("readSequences");
        if (raw instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet())
                result.put(String.valueOf(entry.getKey()), number(entry.getValue()));
        }
        return result;
    }

    private static long sequence(Map<String, Object> message) { return number(message.get("sequence")); }

    private static long number(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        if (value == null) return 0;
        try { return Long.parseLong(String.valueOf(value)); }
        catch (NumberFormatException invalid) { return 0; }
    }

    private static String lastCreatedAt(Map<String, Object> summary) {
        Object last = summary.get("lastMessage");
        return last instanceof Map ? String.valueOf(((Map<?, ?>) last).get("createdAt")) : "";
    }
}
