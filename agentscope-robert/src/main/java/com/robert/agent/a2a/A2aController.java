//package com.robert.agent.a2a;
//
//import com.robert.agent.a2a.client.RemoteAgentProxy;
//import io.agentscope.core.a2a.agent.A2aAgent;
//import io.agentscope.core.message.Msg;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import org.springframework.http.MediaType;
//import org.springframework.web.bind.annotation.GetMapping;
//import org.springframework.web.bind.annotation.PostMapping;
//import org.springframework.web.bind.annotation.RequestBody;
//import org.springframework.web.bind.annotation.RequestMapping;
//import org.springframework.web.bind.annotation.RequestParam;
//import org.springframework.web.bind.annotation.RestController;
//import reactor.core.publisher.Flux;
//
//import java.util.LinkedHashMap;
//import java.util.Map;
//
///**
// * A2A 测试控制器 — 提供 REST 接口测试 A2A 客户端和服务端功能。
// *
// * <h3>接口列表</h3>
// * <ul>
// *   <li>{@code POST /api/a2a/call} — 同步调用远程 Agent</li>
// *   <li>{@code GET /api/a2a/stream} — 流式调用远程 Agent（SSE）</li>
// *   <li>{@code GET /api/a2a/agents} — 列出已缓存的远程 Agent</li>
// *   <li>{@code GET /api/a2a/status} — A2A 服务状态</li>
// * </ul>
// */
//@RestController
//@RequestMapping("/api/a2a")
//public class A2aController {
//
//    private static final Logger log = LoggerFactory.getLogger(A2aController.class);
//
//    private final RemoteAgentProxy remoteAgentProxy;
//
//    public A2aController(RemoteAgentProxy remoteAgentProxy) {
//        this.remoteAgentProxy = remoteAgentProxy;
//    }
//
//    /**
//     * 同步调用远程 Agent。
//     *
//     * <p>请求体示例：
//     * <pre>{@code
//     * {
//     *   "agentName": "remote-agent",
//     *   "message": "你好，请帮我分析数据"
//     * }
//     * }</pre>
//     */
//    @PostMapping("/call")
//    public Map<String, Object> callRemoteAgent(@RequestBody Map<String, String> request) {
//        String agentName = request.get("agentName");
//        String message = request.get("message");
//
//        if (agentName == null || agentName.isBlank()) {
//            return Map.of("error", "agentName is required");
//        }
//        if (message == null || message.isBlank()) {
//            return Map.of("error", "message is required");
//        }
//
//        Map<String, Object> result = new LinkedHashMap<>();
//        result.put("agentName", agentName);
//        result.put("request", message);
//
//        try {
//            long startMs = System.currentTimeMillis();
//            Msg response = remoteAgentProxy.call(agentName, message);
//            long elapsed = System.currentTimeMillis() - startMs;
//
//            result.put("response", response != null ? response.getTextContent() : null);
//            result.put("elapsedMs", elapsed);
//            result.put("success", true);
//        } catch (Exception e) {
//            log.error("[A2A] 调用远程 Agent {} 失败: {}", agentName, e.getMessage(), e);
//            result.put("error", e.getMessage());
//            result.put("success", false);
//        }
//        return result;
//    }
//
//    /**
//     * 流式调用远程 Agent（SSE）。
//     *
//     * <p>通过 SSE 实时推送远程 Agent 的处理事件。</p>
//     */
//    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
//    public Flux<String> streamRemoteAgent(
//            @RequestParam("agentName") String agentName,
//            @RequestParam("message") String message) {
//
//        log.info("[A2A] 流式调用远程 Agent: {}, 消息: {}", agentName, message);
//
//        return remoteAgentProxy.stream(agentName, message)
//                .map(event -> {
//                    Map<String, Object> frame = new LinkedHashMap<>();
//                    frame.put("type", event.getClass().getSimpleName());
//                    frame.put("event", event.toString());
//                    return "data: " + toJson(frame) + "\n\n";
//                })
//                .concatWith(Flux.just("data: {\"type\":\"DONE\"}\n\n"));
//    }
//
//    /**
//     * 列出已缓存的远程 Agent。
//     */
//    @GetMapping("/agents")
//    public Map<String, Object> listCachedAgents() {
//        Map<String, Object> result = new LinkedHashMap<>();
//        Map<String, A2aAgent> cached = remoteAgentProxy.getCachedAgents();
//
//        result.put("cachedCount", cached.size());
//        cached.forEach((name, agent) -> {
//            Map<String, Object> info = new LinkedHashMap<>();
//            info.put("name", agent.getName());
//            info.put("description", agent.getDescription());
//            result.put(name, info);
//        });
//        return result;
//    }
//
//    /**
//     * A2A 服务状态。
//     */
//    @GetMapping("/status")
//    public Map<String, Object> status() {
//        Map<String, Object> result = new LinkedHashMap<>();
//        result.put("server", "running");
//        result.put("a2aEndpoint", "/a2a");
//        result.put("agentCardEndpoint", "/.well-known/agent-card.json");
//        result.put("nacosDiscovery", "enabled");
//        result.put("cachedRemoteAgents", remoteAgentProxy.getCachedAgents().size());
//        return result;
//    }
//
//    private String toJson(Map<String, Object> map) {
//        StringBuilder sb = new StringBuilder("{");
//        boolean first = true;
//        for (Map.Entry<String, Object> entry : map.entrySet()) {
//            if (!first) sb.append(",");
//            sb.append("\"").append(entry.getKey()).append("\":");
//            Object val = entry.getValue();
//            if (val instanceof String) {
//                sb.append("\"").append(escapeJson((String) val)).append("\"");
//            } else {
//                sb.append(val);
//            }
//            first = false;
//        }
//        sb.append("}");
//        return sb.toString();
//    }
//
//    private String escapeJson(String s) {
//        return s.replace("\\", "\\\\")
//                .replace("\"", "\\\"")
//                .replace("\n", "\\n")
//                .replace("\r", "\\r")
//                .replace("\t", "\\t");
//    }
//}
