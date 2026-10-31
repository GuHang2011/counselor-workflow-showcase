package com.example.Kcsj.erp;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Stable identifiers for locally bundled artwork; never accept image URLs from messages. */
final class ChatStickerCatalog {
    private static final Map<String, String> LABELS;
    static {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("received", "收到"); labels.put("okay", "好的");
        labels.put("thanks", "谢谢"); labels.put("hard-work", "辛苦了");
        labels.put("cheer", "加油"); labels.put("great", "点赞");
        labels.put("wait", "稍等一下"); labels.put("done", "已完成");
        labels.put("check-files", "请查收"); labels.put("together", "合作愉快");
        labels.put("morning", "早上好"); labels.put("sorry", "抱歉");
        LABELS = Collections.unmodifiableMap(labels);
    }
    private ChatStickerCatalog() { }
    static String requireId(Object value) {
        if (value == null || "".equals(value)) return "";
        if (!(value instanceof String) || !LABELS.containsKey(value))
            throw new ApiException(400, "请选择系统提供的表情包");
        return (String) value;
    }
    static String fallback(String id) { return "[表情包：" + LABELS.get(id) + "]"; }
}
