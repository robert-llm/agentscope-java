package com.robert.agent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.robert.agent.router.RouterAgent;
import com.robert.agent.router.model.RouterEvent;
import com.robert.agent.router.model.RouterRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * SSE 桥接控制器：把 RouterAgent.stream() 的 Flux<RouterEvent> 直接
 * 转成前端可消费的 text/event-stream。
 *
 * <p>适用于 WebFlux 环境；若使用 Spring MVC + SseEmitter，
 * 可通过 stream().subscribe(event -> emitter.send(...)) 桥接（见下方注释）。</p>
 */
@RestController
@RequestMapping("/api/router")
public class RouterStreamController {

    private final RouterAgent routerAgent;
    private final static ObjectMapper objectMapper = new ObjectMapper();

    public RouterStreamController(RouterAgent routerAgent) {
        this.routerAgent = routerAgent;
    }

    /**
     * 流式对话接口（WebFlux 版本）。
     * 前端用 fetch + ReadableStream 或 EventSource 消费。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@RequestBody Map<String, Object> body) {
        RouterRequest request = new RouterRequest();
        request.setQuestion((String) body.get("question"));
        request.setConversationId((String) body.get("conversationId"));
        request.setMessageId((String) body.get("messageId"));
        request.setLoginName((String) body.get("loginName"));
        request.setDeepThink(Boolean.TRUE.equals(body.get("deepThink")));

        // 可选：参数显式指定路由目标
        Object forced = body.get("targetTeams");
        if (forced instanceof java.util.List<?> list) {
            request.setForcedTargets(list.stream().map(Object::toString).toList());
        }

        return routerAgent.stream(request)
                .map(event -> toSseFrame(event));
    }

    /**
     * 把 RouterEvent 序列化为前端可消费的 JSON 字符串。
     */
    private String toSseFrame(RouterEvent event) {
        try {
            Map<String, Object> frame = new java.util.LinkedHashMap<>();
            frame.put("event", event.getType().name().toLowerCase());
            frame.put("module", event.getModule());
            frame.put("agentId", event.getAgentId());
            frame.put("seq", event.getSeq());
            frame.put("data", event.getPayload());
            return objectMapper.writeValueAsString(frame);
        } catch (Exception e) {
            return "{\"event\":\"error\",\"data\":{\"message\":\"序列化失败\"}}";
        }
    }

    /**
     * Spring MVC + SseEmitter 版本的桥接（如果你不用 WebFlux）：
     * @param body
     * @return
     */
    @PostMapping("/stream2")
    public SseEmitter streamMvc(@RequestBody Map<String, Object> body) {
        SseEmitter emitter = new SseEmitter(0L);
        RouterRequest request = buildRequest(body);

        routerAgent.stream(request)
                .subscribe(
                        event -> {
                            try {
                                emitter.send(SseEmitter.event()
                                        .id(String.valueOf(event.getSeq()))
                                        .name(event.getType().name().toLowerCase())
                                        .data(toSseFrame(event)));
                            } catch (Exception e) {
                                emitter.completeWithError(e);
                            }
                        },
                        emitter::completeWithError,
                        emitter::complete
                );

        return emitter;
    }


    /**
     * 把前端请求体解析为 RouterRequest。
     *
     * <p>前端 body 示例：
     * <pre>
     * {
     *   "question": "集团考勤制度是什么？",
     *   "conversationId": "c_001",
     *   "messageId": "m_001",
     *   "loginName": "alice",
     *   "adGuid": "xxxx",
     *   "orgId": "1001",
     *   "deepThink": true,
     *   "targetTeams": ["eip-agent", "learning-agent"]   // 可选，显式指定路由目标
     * }
     * </pre></p>
     */
    private RouterRequest buildRequest(Map<String, Object> body) {
        RouterRequest request = new RouterRequest();

        request.setQuestion(str(body, "question"));
        request.setConversationId(str(body, "conversationId"));
        request.setMessageId(str(body, "messageId"));
        request.setTaskId(str(body, "taskId"));
        request.setLoginName(str(body, "loginName"));
        request.setAdGuid(str(body, "adGuid"));
        request.setOrgId(str(body, "orgId"));
        request.setDeepThink(Boolean.TRUE.equals(body.get("deepThink")));

        // 可选：参数显式指定路由目标（跳过 LLM 规划）
        Object forced = body.get("targetTeams");
        if (forced instanceof List<?> list) {
            List<String> targets = list.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(Object::toString)
                    .filter(s -> !s.isBlank())
                    .toList();
            if (!targets.isEmpty()) {
                request.setForcedTargets(targets);
            }
        }

        return request;
    }

    private String str(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v != null ? v.toString() : null;
    }
}
