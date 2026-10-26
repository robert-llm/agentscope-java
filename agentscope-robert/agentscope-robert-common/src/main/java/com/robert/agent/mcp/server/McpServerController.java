package com.robert.agent.mcp.server;

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

/**
 * MCP Server 端点（Streamable HTTP 传输）。 让第三方（比如workbuddy）可以和本项目建立连接，进行数据交换
 *
 * <p>实现 MCP 协议的 JSON-RPC 2.0 消息处理，将员工手册知识库问答能力
 * 以 MCP Tool 的形式暴露给外部 MCP 客户端（如 WorkBuddy）。
 *
 * <p>支持的 JSON-RPC 方法：
 * <ul>
 *   <li>{@code initialize} — 协议握手，返回服务器能力和信息</li>
 *   <li>{@code notifications/initialized} — 客户端确认初始化完成</li>
 *   <li>{@code tools/list} — 返回可用工具列表</li>
 *   <li>{@code tools/call} — 调用工具，通过 SSE 流式返回结果</li>
 * </ul>
 *
 * <p>传输协议：MCP Streamable HTTP
 * <ul>
 *   <li>POST /mcp — 发送 JSON-RPC 请求，返回 JSON 或 SSE 流</li>
 *   <li>GET /mcp — SSE 流恢复（预留）</li>
 *   <li>DELETE /mcp — 终止会话（预留）</li>
 * </ul>
 *
 * @Author: luorenshu
 * @Date: 2026/10/23
 */
@RestController
@RequestMapping("/mcp")
@Tag(name = "MCP Server", description = "MCP 协议端点（Streamable HTTP 传输），供 WorkBuddy 等 MCP 客户端调用")
@SecurityRequirement(name = "bearerAuth")
public class McpServerController {

    private static final Logger log = LoggerFactory.getLogger(McpServerController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String MCP_PROTOCOL_VERSION = "2025-03-26";

    private final EmployeeHandbookService employeeHandbookService;

    /** 活跃会话存储（sessionId → 会话元数据） */
    private final Map<String, McpSession> sessions = new ConcurrentHashMap<>();

    public McpServerController(EmployeeHandbookService employeeHandbookService) {
        this.employeeHandbookService = employeeHandbookService;
    }

    // ==================== MCP Streamable HTTP 端点 ====================

    /**
     * MCP 主端点 — 处理所有 JSON-RPC 2.0 请求。
     *
     * <p>对于 {@code initialize}、{@code tools/list} 等简单方法，返回 JSON 响应；
     * 对于 {@code tools/call}，返回 SSE 流式响应。
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "MCP JSON-RPC 端点",
            description = "处理 MCP 协议消息（initialize / tools/list / tools/call）")
    public ResponseEntity<?> handleMcpMessage(
            @RequestBody String body,
            @RequestHeader(value = "Mcp-Session-Id", required = false) String sessionIdHeader) {

        try {
            JsonNode request = MAPPER.readTree(body);
            String method = request.path("method").asText("");
            JsonNode params = request.path("params");
            Object id = request.has("id") ? extractId(request.get("id")) : null;
            boolean isNotification = !request.has("id");

            log.info("[MCP] 收到请求: method={}, id={}, isNotification={}, sessionId={}",
                    method, id, isNotification, sessionIdHeader);

            // 确定或创建会话
            String sessionId = sessionIdHeader;
            if (sessionId == null || sessionId.isBlank()) {
                sessionId = UUID.randomUUID().toString();
            }

            return switch (method) {
                case "initialize" -> handleInitialize(id, sessionId);
                case "notifications/initialized" -> handleNotificationInitialized(sessionId);
                case "tools/list" -> handleToolsList(id);
                case "tools/call" -> handleToolsCall(id, params, sessionId);
                default -> {
                    if (isNotification) {
                        yield ResponseEntity.ok().build();
                    }
                    yield ResponseEntity.ok(methodNotFound(id, method));
                }
            };
        } catch (Exception e) {
            log.error("[MCP] 消息处理失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(parseError(null, e.getMessage()));
        }
    }

    /**
     * GET 端点 — SSE 流恢复（预留，当前返回 405）。
     */
    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Void> resumeStream(
            @RequestHeader(value = "Mcp-Session-Id", required = false) String sessionId) {
        log.info("[MCP] GET 请求（SSE 流恢复）: sessionId={}", sessionId);
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
    }

    /**
     * DELETE 端点 — 终止会话（预留）。
     */
    @DeleteMapping
    public ResponseEntity<Void> terminateSession(
            @RequestHeader(value = "Mcp-Session-Id") String sessionId) {
        log.info("[MCP] DELETE 请求（终止会话）: sessionId={}", sessionId);
        sessions.remove(sessionId);
        return ResponseEntity.ok().build();
    }

    // ==================== JSON-RPC 方法处理 ====================

    /**
     * 处理 initialize 握手。
     */
    private ResponseEntity<Map<String, Object>> handleInitialize(Object id, String sessionId) {
        log.info("[MCP] initialize: 协议版本={}, sessionId={}", MCP_PROTOCOL_VERSION, sessionId);

        sessions.put(sessionId, new McpSession(sessionId, "initialized"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", MCP_PROTOCOL_VERSION);
        result.put("capabilities", Map.of(
                "tools", Map.of("listChanged", false)
        ));
        result.put("serverInfo", Map.of(
                "name", "employee-handbook-mcp",
                "version", "1.0.0"
        ));

        return ResponseEntity.ok()
                .header("Mcp-Session-Id", sessionId)
                .body(jsonRpcResponse(id, result));
    }

    /**
     * 处理 notifications/initialized（客户端确认，无需响应体）。
     */
    private ResponseEntity<Void> handleNotificationInitialized(String sessionId) {
        log.info("[MCP] notifications/initialized: 客户端已确认初始化, sessionId={}", sessionId);
        McpSession session = sessions.get(sessionId);
        if (session != null) {
            session.status = "active";
        }
        return ResponseEntity.ok()
                .header("Mcp-Session-Id", sessionId)
                .build();
    }

    /**
     * 处理 tools/list — 返回可用工具定义。
     */
    private ResponseEntity<Map<String, Object>> handleToolsList(Object id) {
        log.info("[MCP] tools/list");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tools", List.of(createHandbookToolDefinition()));

        return ResponseEntity.ok(Map.of(
                "jsonrpc", "2.0",
                "result", result,
                "id", id
        ));
    }

    /**
     * 处理 tools/call — 调用工具并通过 SSE 流式返回结果。
     *
     * <p>MCP Streamable HTTP 传输中，tools/call 的响应可以是 SSE 流：
     * 服务端返回 Content-Type: text/event-stream，通过 SSE 事件发送 JSON-RPC 结果。
     */
    private ResponseEntity<?> handleToolsCall(Object id, JsonNode params, String sessionId) {
        String toolName = params.path("name").asText("");
        JsonNode arguments = params.path("arguments");

        log.info("[MCP] tools/call: tool={}, sessionId={}", toolName, sessionId);

        if (!"handbook_query".equals(toolName)) {
            return ResponseEntity.ok(errorResponse(id, -32601, "工具不存在: " + toolName));
        }

        String message = arguments.path("message").asText("");
        if (message.isBlank()) {
            return ResponseEntity.ok(errorResponse(id, -32602, "message 参数不能为空"));
        }

        if (!employeeHandbookService.isReady()) {
            return ResponseEntity.ok(errorResponse(id, -32603, "员工手册知识库服务未初始化"));
        }

        // 创建 SSE 发射器（5 分钟超时）
        SseEmitter emitter = new SseEmitter(Duration.ofMinutes(5).toMillis());
        String streamSessionId = sessionId;

        // 发送会话建立事件
        try {
            emitter.send(SseEmitter.event()
                    .name("session-established")
                    .data(""));
        } catch (Exception e) {
            log.warn("[MCP] 发送 session-established 事件失败", e);
        }

        // 调用知识库服务并流式处理结果
        Flux<AgentEvent> eventFlux = employeeHandbookService.streamChat(message);
        final StringBuilder textBuffer = new StringBuilder();

        eventFlux.subscribe(
                event -> {
                    try {
                        if (event instanceof TextBlockDeltaEvent delta) {
                            textBuffer.append(delta.getDelta());
                        } else if (event instanceof TextBlockEndEvent) {
                            // 忽略，等待完成回调统一处理
                        }
                    } catch (Exception e) {
                        log.warn("[MCP] 处理 SSE 事件失败", e);
                    }
                },
                error -> {
                    log.error("[MCP] 知识库查询错误: sessionId={}", streamSessionId, error);
                    try {
                        emitter.send(SseEmitter.event()
                                .name("message")
                                .data(MAPPER.writeValueAsString(
                                        errorResponse(id, -32603, "查询失败: " + error.getMessage()))));
                    } catch (Exception ignored) {
                        // 客户端可能已断开
                    }
                    emitter.completeWithError(error);
                },
                () -> {
                    // 完成：发送最终 JSON-RPC 结果
                    String replyText = textBuffer.toString();
                    try {
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("content", List.of(
                                Map.of("type", "text", "text", replyText)
                        ));
                        result.put("isError", false);

                        String jsonRpcResult = MAPPER.writeValueAsString(Map.of(
                                "jsonrpc", "2.0",
                                "result", result,
                                "id", id
                        ));

                        emitter.send(SseEmitter.event()
                                .name("message")
                                .data(jsonRpcResult));
                    } catch (Exception e) {
                        log.error("[MCP] 发送最终结果失败: sessionId={}", streamSessionId, e);
                    }
                    log.info("[MCP] tools/call 完成: sessionId={}, replyLength={}",
                            streamSessionId, replyText.length());
                    emitter.complete();
                }
        );

        emitter.onTimeout(() -> {
            log.warn("[MCP] SSE 超时: sessionId={}", streamSessionId);
            emitter.complete();
        });
        emitter.onError(e -> log.debug("[MCP] SSE 连接异常: {}", e.getMessage()));

        return ResponseEntity.ok()
                .header("Mcp-Session-Id", sessionId)
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(emitter);
    }

    // ==================== 辅助方法 ====================

    private Map<String, Object> jsonRpcResponse(Object id, Map<String, Object> result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("result", result);
        response.put("id", id);
        return response;
    }

    private Map<String, Object> errorResponse(Object id, int code, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("error", Map.of("code", code, "message", message));
        response.put("id", id);
        return response;
    }

    private Map<String, Object> methodNotFound(Object id, String method) {
        return errorResponse(id, -32601, "方法不存在: " + method);
    }

    private Map<String, Object> parseError(Object id, String message) {
        return errorResponse(id, -32700, "解析错误: " + message);
    }

    /**
     * 从 JSON 节点提取 id 值（可能是数字或字符串）。
     */
    private Object extractId(JsonNode idNode) {
        if (idNode.isNumber()) return idNode.numberValue();
        if (idNode.isTextual()) return idNode.asText();
        return idNode.toString();
    }

    /**
     * 创建 handbook_query 工具定义（MCP Tool Schema）。
     */
    private Map<String, Object> createHandbookToolDefinition() {
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", "handbook_query");
        tool.put("description", "基于员工手册知识库的问答工具。可以回答关于公司政策、福利、休假、考勤等员工手册相关的问题。");
        tool.put("inputSchema", Map.of(
                "type", "object",
                "properties", Map.of(
                        "message", Map.of(
                                "type", "string",
                                "description", "用户要咨询的问题，例如：公司的年假政策是什么？"
                        )
                ),
                "required", List.of("message")
        ));
        return tool;
    }

    /**
     * MCP 会话信息。
     */
    private static class McpSession {
        final String id;
        volatile String status;

        McpSession(String id, String status) {
            this.id = id;
            this.status = status;
        }
    }
}
