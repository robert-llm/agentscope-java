package com.robert.agent.router;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SSE 事件序列化工具（轻量实现）。
 * 如项目已有 EventObjectConvert，请替换为现有实现。
 */
public final class EventObjectSerializer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EventObjectSerializer() {
    }

    public static String serialize(String event, String conversationId, String messageId,
                                   String taskId, String answer, Map<String, Object> data) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", event);
        payload.put("conversation_id", conversationId);
        payload.put("message_id", messageId);
        payload.put("created_at", System.currentTimeMillis() / 1000);
        if (taskId != null) {
            payload.put("task_id", taskId);
        }
        if (answer != null) {
            payload.put("answer", answer);
        }
        if (data != null && !data.isEmpty()) {
            payload.put("data", data);
        }
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("SSE 事件序列化失败", e);
        }
    }
}
