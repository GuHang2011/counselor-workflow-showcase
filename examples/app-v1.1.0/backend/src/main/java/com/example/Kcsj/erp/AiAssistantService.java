package com.example.Kcsj.erp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.util.*;

import static com.example.Kcsj.erp.ErpStore.*;

/**
 * AI helper with a deterministic local fallback. It intentionally does not
 * persist prompts or generated text; callers must review generated content
 * before publishing it as a notice, task, or work record.
 */
@Service
public class AiAssistantService {
    private final ObjectMapper json;
    private final AiModelConfiguration configuration;
    @Value("${erp.ai.connect-timeout-ms:1500}") private int connectTimeoutMs;
    @Value("${erp.ai.read-timeout-ms:6000}") private int readTimeoutMs;

    public AiAssistantService(ObjectMapper json,AiModelConfiguration configuration) { this.json=json;this.configuration=configuration; }
    public Map<String,Object> status(){return configuration.view(false);}
    public Map<String,Object> adminConfiguration(){return configuration.view(true);}
    public Map<String,Object> configure(Map<String,Object> input){return configuration.configure(input);}

    public Map<String,Object> assist(Map<String,Object> body) {
        String action = value(body, "action", "scenario", "type").toLowerCase(Locale.ROOT);
        if (action.isEmpty()) action = "chat";
        if (action.length() > 40) throw new ApiException(400, "AI 场景无效");
        action = normalizeAction(action);
        if (!isSupportedAction(action)) throw new ApiException(400, "AI 场景无效");
        // `subject` is used by the legacy /ai/notice-draft compatibility
        // endpoint; treat it as the topic so the common input validation does
        // not reject otherwise valid legacy requests.
        String prompt = value(body, "prompt", "topic", "subject", "question", "input");
        String details = value(body, "details", "content", "description");
        Map<String,Object> context = new LinkedHashMap<>();
        if (body.get("context") instanceof Map) context.putAll(castMap(body.get("context")));
        // The mobile and desktop clients may submit these common fields at the top level.
        // Normalize them here so both clients produce the same draft.
        for (String key : Arrays.asList("audience", "deadline", "tone")) {
            if (!context.containsKey(key) && body.get(key) != null) context.put(key, body.get(key));
        }
        if (prompt.isEmpty()) prompt = value(context, "topic", "subject", "title", "question");
        if (prompt.length() > 10000 || details.length() > 20000 || context.toString().length() > 12000) throw new ApiException(400, "AI 输入内容过长");
        if (prompt.isEmpty() && details.isEmpty()) throw new ApiException(400, "请填写 AI 主题或工作说明");
        String userPrompt = buildPrompt(action, prompt, details, context);

        // Keep the deterministic local helper available when the external model is disabled.
        // This lets the office workflow remain usable while making the deployment state explicit.
        if (!configuration.current().enabled) {
            Map<String,Object> fallback = local(action, prompt, details, context);
            fallback.put("warning", "\u7ba1\u7406\u5458\u5df2\u5173\u95ed AI \u8f85\u52a9\u529f\u80fd\uff0c\u5f53\u524d\u4f7f\u7528\u672c\u5730\u6a21\u677f\u751f\u6210\uff0c\u8bf7\u4eba\u5de5\u5ba1\u6838");
            return fallback;
        }

        // Never send data to a model unless the deployment explicitly opts in.
        if (configuration.allowsExternalData() && externalReady()) {
            Map<String,Object> external = callExternal(action, userPrompt);
            if (external != null) return external;
            Map<String,Object> fallback=local(action,prompt,details,context);
            fallback.put("warning","模型调用未成功，已改用本地模板，请核对内容后使用。");
            return fallback;
        }
        return local(action, prompt, details, context);
    }

    private Map<String,Object> local(String action, String prompt, String details, Map<String,Object> context) {
        if(action.equals("text_write")||action.equals("text_polish")) {
            String source=details.isEmpty()?prompt:details;
            String content=action.equals("text_polish")?source.trim().replaceAll("[ \\t]+", " "):prompt+"\n\n"+source+"\n\n后续安排：请补充责任人、时间及办理方式。";
            return result("template",content,action.equals("text_write")?"文本写作草稿":"文本整理草稿",content,Arrays.asList("核对事实和日期","按用途调整表达"),true,"当前为本地文本整理模板；语义润色需配置外部或校内模型");
        }
        if (action.equals("notice_draft")) {
            String audience = first(context, "audience", "对象", "全体学生");
            String deadline = first(context, "deadline", "截止时间", "");
            String tone = first(context, "tone", "语气", "formal");
            String title = prompt.isEmpty() ? "工作通知" : prompt;
            StringBuilder content = new StringBuilder();
            content.append(audience).append("：\n\n");
            if (!details.isEmpty()) content.append(details.trim()).append("\n\n");
            if (!deadline.isEmpty()) content.append("办理截止时间：").append(deadline.trim()).append("。\n\n");
            content.append(tone.equalsIgnoreCase("warm") ? "请按时完成，办理过程中如有困难请及时联系辅导员。" : "请在截止时间前完成办理，并通过工作台查看提交状态。");
            return result("template", content.toString(), title, content.toString(), Arrays.asList("核对对象范围", "核对截止时间", "发布前确认附件和办理入口"), true,
                    "本内容由校内模板生成，请核对后再发布");
        }
        if (action.equals("task_breakdown")) {
            String source = !details.isEmpty() ? details : prompt;
            List<String> pieces = splitItems(source);
            List<String> suggestions = new ArrayList<>();
            for (int i = 0; i < pieces.size(); i++) suggestions.add((i + 1) + ". " + pieces.get(i));
            String answer = suggestions.isEmpty() ? "请补充任务背景、交付物和截止时间，再生成任务清单。" : "建议拆分为：\n" + joinLines(suggestions);
            return result("template", answer, prompt.isEmpty() ? "任务拆分建议" : prompt, answer, suggestions, true,
                    "AI 仅提供拆分建议，责任人和时间需人工确认");
        }
        if (action.equals("work_summary")) {
            String source = !details.isEmpty() ? details : prompt;
            String answer = source.isEmpty() ? "请粘贴工作记录、会议纪要或办理进度后生成摘要。" : summarize(source);
            return result("template", answer, "工作摘要", answer, Arrays.asList("补充未完成事项", "确认数据和日期", "标记需要跟进的学生或任务"), true,
                    "摘要不替代正式记录，请人工核对事实和隐私信息");
        }
        if (action.equals("policy_answer") || action.equals("chat")) {
            String question = prompt.isEmpty() ? details : prompt;
            String answer = chatAnswer(question);
            return result("template", answer, "", answer, Arrays.asList("以校内制度原文为准", "涉及学生权益的决定需人工审核"), true,
                    "当前使用本地规则模板，未调用外部模型");
        }
        return result("template", "暂不支持该 AI 场景，请选择通知草稿、任务拆分或工作摘要。", "", "", Collections.emptyList(), true,
                "未知场景已降级为模板提示");
    }

    public Map<String,Object> newsDraft(String facts,List<Map<String,Object>> images){
        String prompt="根据已核实活动事实生成校园新闻初稿，使用标题、导语、正文、结尾结构。不得捏造人物、讲话、时间、数字或从照片推断身份与政治信息。缺失事实以待核实标记。\n"+facts;
        if(configuration.allowsExternalData()&&externalReady()){Map<String,Object> result=callExternal("news_draft",prompt,images);if(result!=null)return result;}
        String content="【导语】\n"+facts+"\n\n【活动开展】\n请补充已核实的活动过程与现场细节。\n\n【后续安排】\n请补充下一步工作；人名、引语和日期需核实后填写。";
        return result("template",content,"校园新闻初稿",content,Collections.singletonList("核对活动事实和图片授权"),true,"当前使用本地新闻模板，未分析图片；图片可用于初稿排版。");
    }

    private Map<String,Object> callExternal(String action, String userPrompt) {return callExternal(action,userPrompt,Collections.emptyList());}
    private Map<String,Object> callExternal(String action, String userPrompt,List<Map<String,Object>> images) {
        AiModelConfiguration.Settings settings=configuration.current();
        if(!configuration.allowsExternalData()||!settings.ready())return null;
        try {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory(){
                @Override protected void prepareConnection(java.net.HttpURLConnection connection,String method) throws java.io.IOException {
                    super.prepareConnection(connection,method);connection.setInstanceFollowRedirects(false);
                }
            };
            factory.setConnectTimeout(Math.max(200, connectTimeoutMs));
            factory.setReadTimeout(Math.max(500, readTimeoutMs));
            RestTemplate client = new RestTemplate(factory);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(settings.apiKey);
            Object content=userPrompt;
            if(!images.isEmpty()){List<Map<String,Object>> parts=new ArrayList<>();parts.add(map("type","text","text",userPrompt));parts.addAll(images);content=parts;}
            Map<String,Object> message = map("role", "user", "content", content);
            Map<String,Object> request = map("model", settings.model, "temperature", 0.2, "max_tokens", 2000,
                    "messages", Collections.singletonList(message));
            JsonNode root = client.execute(settings.endpoint(),HttpMethod.POST,outgoing->{
                outgoing.getHeaders().putAll(headers);
                outgoing.getBody().write(json.writeValueAsBytes(request));
            },incoming->{
                if(!incoming.getStatusCode().is2xxSuccessful())return null;
                java.io.ByteArrayOutputStream buffer=new java.io.ByteArrayOutputStream();
                byte[] chunk=new byte[8192];int count;
                while((count=incoming.getBody().read(chunk))!=-1){
                    if(buffer.size()+count>1024*1024)throw new java.io.IOException("Model response exceeds limit");
                    buffer.write(chunk,0,count);
                }
                return json.readTree(buffer.toByteArray());
            });
            if(root==null)return null;
            String answer = root.path("choices").path(0).path("message").path("content").asText("");
            if (answer.trim().isEmpty()) answer = root.path("output").asText("");
            if (answer.trim().isEmpty()) return null;
            return result("external", answer.trim(), "", answer.trim(), Collections.emptyList(), true,
                    "外部模型仅生成草稿，发布前必须人工复核");
        } catch (Exception ignored) {
            // An unreachable or malformed model must never make the office workflow unavailable.
            return null;
        }
    }

    private String buildPrompt(String action, String prompt, String details, Map<String,Object> context) {
        return "你是高校辅导员办公助手。只生成草稿和建议，不作资格、处分、诊断或其他最终决定。场景：" + action
                + "\n主题：" + prompt + "\n补充信息：" + details + "\n上下文：" + context;
    }

    private String normalizeAction(String action) {
        if (Arrays.asList("notice", "notice-draft", "notice_draft", "announcement").contains(action)) return "notice_draft";
        if (Arrays.asList("task", "task-breakdown", "task_breakdown", "todo").contains(action)) return "task_breakdown";
        if (Arrays.asList("summary", "worklog", "work-summary", "work_summary", "meeting_summary").contains(action)) return "work_summary";
        if (Arrays.asList("policy", "policy-answer", "policy_answer").contains(action)) return "policy_answer";
        return action;
    }

    private boolean isSupportedAction(String action) {
        return action.equals("notice_draft") || action.equals("task_breakdown")
                || action.equals("work_summary") || action.equals("policy_answer")
                || action.equals("chat") || action.equals("text_write") || action.equals("text_polish");
    }

    private boolean externalReady() {return configuration.current().ready();}

    private Map<String,Object> result(String mode, String answer, String title, String content, List<String> suggestions, boolean review, String warning) {
        return map("mode", mode, "answer", answer, "title", title, "content", content, "sources", Collections.emptyList(),
                "suggestions", suggestions, "requiresHumanReview", review, "modelConfigured", externalReady(), "warning", warning);
    }
    private String chatAnswer(String prompt) {
        String lower = prompt.toLowerCase(Locale.ROOT);
        if (lower.contains("通知") || lower.contains("公告")) return "可以使用“通知草稿”场景，填写对象、办理事项和截止时间后再发布。";
        if (lower.contains("任务") || lower.contains("待办")) return "可以使用“任务拆分”场景，将目标、责任人、交付物和截止时间分别确认。";
        if (lower.contains("总结") || lower.contains("纪要")) return "可以使用“工作摘要”场景，粘贴记录后人工核对日期、对象和未完成事项。";
        return "我可以协助整理通知、拆分待办、生成工作摘要。请补充具体事项、对象和时间。";
    }
    private String summarize(String source) {
        String cleaned = source.replaceAll("\\s+", " ").trim();
        if (cleaned.length() > 1200) cleaned = cleaned.substring(0, 1200) + "…";
        return "工作摘要：\n" + cleaned + "\n\n待核对：责任人、截止时间、未完成事项及需要跟进对象。";
    }
    private List<String> splitItems(String source) {
        List<String> out = new ArrayList<>();
        for (String item : source.split("[\\r\\n；;。]+")) {
            String value = item.trim().replaceFirst("^[-*•]\\s*", "");
            if (!value.isEmpty()) out.add(value.length() > 240 ? value.substring(0, 240) + "…" : value);
        }
        return out;
    }
    private String joinLines(List<String> lines) { StringBuilder out = new StringBuilder(); for (String line : lines) out.append(line).append('\n'); return out.toString().trim(); }
    private String value(Map<String,Object> body, String... keys) { for (String key : keys) { Object value = body.get(key); if (value != null) return String.valueOf(value).trim(); } return ""; }
    private String first(Map<String,Object> body, String key, String alternate, String fallback) { String value = value(body, key, alternate); return value.isEmpty() ? fallback : value; }
    @SuppressWarnings("unchecked") private Map<String,Object> castMap(Object value) { return (Map<String,Object>) value; }
}
