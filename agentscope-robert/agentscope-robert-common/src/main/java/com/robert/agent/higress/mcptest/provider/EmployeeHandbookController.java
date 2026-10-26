package com.robert.agent.higress.mcptest.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.robert.agent.higress.mcptest.service.EmployeeHandbookService;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.message.Msg;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 员工手册知识库 Controller。
 *
 * <p>提供基于员工手册文档的 RAG 问答接口，支持流式和同步两种模式。
 *
 * @Author: luorenshu
 * @Date: 2026/10/21
 */
@RestController
@RequestMapping("/mcp/api/handbook")
@Tag(name = "EmployeeHandbook", description = "员工手册知识库问答接口")
@SecurityRequirement(name = "bearerAuth")
public class EmployeeHandbookController {

    private static final Logger log = LoggerFactory.getLogger(EmployeeHandbookController.class);

    /**
     * 文本增量缓冲最小块大小，累积到该大小后再推送一次 SSE 事件，减少事件数量。
     */
    private static final int MIN_DELTA_CHUNK_SIZE = 50;

    private final EmployeeHandbookService employeeHandbookService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicLong sessionCounter = new AtomicLong(0);

    // MCP 协议支持
    private static final String MCP_PROTOCOL_VERSION = "2025-03-26";
    private final Map<String, String> mcpSessions = new ConcurrentHashMap<>();

    public EmployeeHandbookController(EmployeeHandbookService employeeHandbookService) {
        this.employeeHandbookService = employeeHandbookService;
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "员工手册流式对话 / MCP 协议端点",
            description = "支持普通 REST 请求（message 字段）和 MCP 协议请求（jsonrpc 字段）",
            security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<?> handbookStream(
            @RequestBody String rawBody,
            @RequestHeader(value = "Mcp-Session-Id", required = false) String mcpSessionId) {

        // 检测是否为 MCP 协议请求（包含 "jsonrpc":"2.0" 字段）
        if (isMcpRequest(rawBody)) {
            log.info(">>> [员工手册] 检测到 MCP 协议请求");
            return handleMcpRequest(rawBody, mcpSessionId);
        }

        // 普通 REST 业务请求
        return handleRestStreamRequest(rawBody);
    }

    /**
     * 检测请求体是否为 MCP 协议消息。
     */
    private boolean isMcpRequest(String body) {
        if (body == null || body.isBlank()) return false;
        try {
            JsonNode node = objectMapper.readTree(body);
            return "2.0".equals(node.path("jsonrpc").asText(""));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 处理 MCP 协议请求。
     */
    private ResponseEntity<?> handleMcpRequest(String body, String sessionIdHeader) {
        try {
            JsonNode request = objectMapper.readTree(body);
            String method = request.path("method").asText("");
            JsonNode params = request.path("params");
            Object id = request.has("id") ? extractId(request.get("id")) : null;

            String sessionId = (sessionIdHeader != null && !sessionIdHeader.isBlank())
                    ? sessionIdHeader : UUID.randomUUID().toString();

            log.info("[MCP] method={}, id={}, sessionId={}", method, id, sessionId);

            return switch (method) {
                case "initialize" -> handleMcpInitialize(id, sessionId);
                case "notifications/initialized" -> handleMcpNotification(sessionId);
                case "tools/list" -> handleMcpToolsList(id);
                case "tools/call" -> handleMcpToolsCall(id, params, sessionId);
                default -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(mcpErrorResponse(id, -32601, "方法不存在: " + method));
            };
        } catch (Exception e) {
            log.error("[MCP] 消息处理失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(mcpErrorResponse(null, -32700, "解析错误: " + e.getMessage()));
        }
    }

    private ResponseEntity<Map<String, Object>> handleMcpInitialize(Object id, String sessionId) {
        mcpSessions.put(sessionId, "initialized");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", MCP_PROTOCOL_VERSION);
        result.put("capabilities", Map.of("tools", Map.of("listChanged", false)));
        result.put("serverInfo", Map.of("name", "employee-handbook-mcp", "version", "1.0.0"));
        return ResponseEntity.ok()
                .header("Mcp-Session-Id", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(mcpSuccessResponse(id, result));
    }

    private ResponseEntity<Void> handleMcpNotification(String sessionId) {
        mcpSessions.put(sessionId, "active");
        return ResponseEntity.ok().header("Mcp-Session-Id", sessionId).build();
    }

    private ResponseEntity<Map<String, Object>> handleMcpToolsList(Object id) {
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", "handbook_query");
        tool.put("description", "基于员工手册知识库的问答工具");
        tool.put("inputSchema", Map.of(
                "type", "object",
                "properties", Map.of("message", Map.of("type", "string", "description", "用户要咨询的问题")),
                "required", List.of("message")
        ));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(mcpSuccessResponse(id, Map.of("tools", List.of(tool))));
    }

    private ResponseEntity<?> handleMcpToolsCall(Object id, JsonNode params, String sessionId) {
        String toolName = params.path("name").asText("");
        String message = params.path("arguments").path("message").asText("");

        if (!"handbook_query".equals(toolName)) {
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(mcpErrorResponse(id, -32601, "工具不存在: " + toolName));
        }
        if (message.isBlank()) {
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(mcpErrorResponse(id, -32602, "message 参数不能为空"));
        }
        if (!employeeHandbookService.isReady()) {
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(mcpErrorResponse(id, -32603, "员工手册知识库服务未初始化"));
        }

        SseEmitter emitter = new SseEmitter(Duration.ofMinutes(5).toMillis());
        try {
            emitter.send(SseEmitter.event().name("session-established").data(""));
        } catch (Exception e) {
            log.warn("[MCP] 发送 session-established 失败", e);
        }

        Flux<AgentEvent> eventFlux = employeeHandbookService.streamChat(message);
        final StringBuilder textBuffer = new StringBuilder();

        eventFlux.subscribe(
                event -> {
                    if (event instanceof TextBlockDeltaEvent delta) {
                        textBuffer.append(delta.getDelta());
                    }
                },
                error -> {
                    try {
                        emitter.send(SseEmitter.event().name("message")
                                .data(objectMapper.writeValueAsString(
                                        mcpErrorResponse(id, -32603, "查询失败: " + error.getMessage()))));
                    } catch (Exception ignored) {}
                    emitter.completeWithError(error);
                },
                () -> {
                    try {
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("content", List.of(Map.of("type", "text", "text", textBuffer.toString())));
                        result.put("isError", false);
                        emitter.send(SseEmitter.event().name("message")
                                .data(objectMapper.writeValueAsString(mcpSuccessResponse(id, result))));
                    } catch (Exception e) {
                        log.error("[MCP] 发送结果失败", e);
                    }
                    emitter.complete();
                }
        );

        emitter.onTimeout(emitter::complete);
        return ResponseEntity.ok()
                .header("Mcp-Session-Id", sessionId)
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(emitter);
    }

    private Map<String, Object> mcpSuccessResponse(Object id, Map<String, Object> result) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("jsonrpc", "2.0");
        r.put("result", result);
        r.put("id", id);
        return r;
    }

    private Map<String, Object> mcpErrorResponse(Object id, int code, String message) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("jsonrpc", "2.0");
        r.put("error", Map.of("code", code, "message", message));
        r.put("id", id);
        return r;
    }

    private Object extractId(JsonNode idNode) {
        if (idNode.isNumber()) return idNode.numberValue();
        if (idNode.isTextual()) return idNode.asText();
        return idNode.toString();
    }

    /**
     * 处理普通 REST 流式对话请求。
     */
    private ResponseEntity<SseEmitter> handleRestStreamRequest(String rawBody) {
        HandbookChatRequest request;
        try {
            request = objectMapper.readValue(rawBody, HandbookChatRequest.class);
        } catch (Exception e) {
            log.warn("[员工手册] 请求体解析失败: {}", e.getMessage());
            return createErrorEmitter("请求体格式错误，需要 JSON 格式 {\"message\": \"...\"}");
        }

        String message = request.getMessage();
        if (message == null || message.isBlank()) {
            return createErrorEmitter("message 参数不能为空");
        }

        if (!employeeHandbookService.isReady()) {
            return createErrorEmitter("员工手册知识库服务未初始化，请检查配置");
        }

        // 5 分钟超时，知识库问答可能需要较长时间
        SseEmitter emitter = new SseEmitter(Duration.ofMinutes(5).toMillis());
        String sessionId = "handbook-session-" + sessionCounter.incrementAndGet();
        log.info(">>> [员工手册流式对话] message={}, session={}", message, sessionId);

        Flux<AgentEvent> eventFlux = employeeHandbookService.streamChat(message);

        // 文本增量缓冲区：累积小段 TextBlockDelta 事件后合并推送，减少 SSE 事件数量
        final StringBuilder textBuffer = new StringBuilder();

        eventFlux.subscribe(
                event -> {
                    try {
                        if (event instanceof TextBlockDeltaEvent delta) {
                            textBuffer.append(delta.getDelta());
                            if (textBuffer.length() >= MIN_DELTA_CHUNK_SIZE) {
                                emitter.send(SseEmitter.event()
                                        .name("TEXT_BLOCK_DELTA")
                                        .data(Map.of("delta", textBuffer.toString())));
                                textBuffer.setLength(0);
                            }
                        } else if (event instanceof TextBlockEndEvent) {
                            // 刷新缓冲区中剩余的文本
                            if (textBuffer.length() > 0) {
                                emitter.send(SseEmitter.event()
                                        .name("TEXT_BLOCK_DELTA")
                                        .data(Map.of("delta", textBuffer.toString())));
                                textBuffer.setLength(0);
                            }
                            emitter.send(SseEmitter.event()
                                    .name("TEXT_BLOCK_END")
                                    .data(Map.of("type", "TEXT_BLOCK_END")));
                        } else {
                            // 其他事件：先刷新缓冲区，再转发
                            if (textBuffer.length() > 0) {
                                emitter.send(SseEmitter.event()
                                        .name("TEXT_BLOCK_DELTA")
                                        .data(Map.of("delta", textBuffer.toString())));
                                textBuffer.setLength(0);
                            }
                            String json = objectMapper.writeValueAsString(event);
                            emitter.send(SseEmitter.event()
                                    .name(event.getType().name())
                                    .data(json));
                        }
                    } catch (Exception e) {
                        log.warn("发送 SSE 事件失败: {}", e.getMessage());
                        emitter.completeWithError(e);
                    }
                },
                error -> {
                    log.error("[员工手册流式对话] 错误 (session={})", sessionId, error);
                    try {
                        emitter.send(SseEmitter.event()
                                .name("error")
                                .data(Map.of("error", error.getMessage())));
                    } catch (Exception ignored) {
                        // 客户端可能已断开连接
                    }
                    emitter.completeWithError(error);
                },
                () -> {
                    // 刷新剩余缓冲文本
                    try {
                        if (textBuffer.length() > 0) {
                            emitter.send(SseEmitter.event()
                                    .name("TEXT_BLOCK_DELTA")
                                    .data(Map.of("delta", textBuffer.toString())));
                        }
                        emitter.send(SseEmitter.event()
                                .name("done")
                                .data(Map.of("status", "completed", "sessionId", sessionId)));
                    } catch (Exception ignored) {
                        // best effort
                    }
                    log.info("<<< [员工手册流式对话] 完成 (session={})", sessionId);
                    emitter.complete();
                });

        emitter.onTimeout(emitter::complete);
        emitter.onError(e -> log.debug("SSE 连接异常: {}", e.getMessage()));

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(emitter);
    }

    /**
     * 创建错误 SSE 响应。
     */
    private ResponseEntity<SseEmitter> createErrorEmitter(String errorMsg) {
        SseEmitter emitter = new SseEmitter(1000L);
        try {
            emitter.send(SseEmitter.event().name("error").data(Map.of("error", errorMsg)));
            emitter.complete();
        } catch (Exception ignored) {
            emitter.completeWithError(ignored);
        }
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(emitter);
    }

    @PostMapping(value = "/chat")
    @Operation(summary = "员工手册同步问答",
            description = "基于员工手册知识库的同步问答接口，返回完整回答",
            security = @SecurityRequirement(name = "bearerAuth"))
    public Map<String, Object> handbookChat(@RequestBody HandbookChatRequest request) {
        String message = request.getMessage();
        if (message == null || message.isBlank()) {
            return Map.of("error", "message 参数不能为空");
        }

        if (!employeeHandbookService.isReady()) {
            return Map.of("error", "员工手册知识库服务未初始化，请检查配置");
        }

        log.info("[员工手册同步问答] message={}", message);

        try {
            Msg response = employeeHandbookService.chat(message);
            String replyText = "";
            if (response != null && response.getContent() != null) {
                replyText = response.getContent().stream()
                        .filter(block -> block instanceof io.agentscope.core.message.TextBlock)
                        .map(block -> ((io.agentscope.core.message.TextBlock) block).getText())
                        .reduce("", (a, b) -> a + b);
            }
            return Map.of(
                    "reply", replyText,
                    "status", "success"
            );
        } catch (Exception e) {
            log.error("[员工手册同步问答] 错误", e);
            return Map.of("error", "问答失败: " + e.getMessage(), "status", "error");
        }
    }

    @GetMapping("/status")
    @Operation(summary = "员工手册服务状态", description = "检查员工手册知识库服务是否已就绪")
    public Map<String, Object> handbookStatus() {
        return Map.of(
                "ready", employeeHandbookService.isReady(),
                "service", "employee-handbook-rag"
        );
    }
}
