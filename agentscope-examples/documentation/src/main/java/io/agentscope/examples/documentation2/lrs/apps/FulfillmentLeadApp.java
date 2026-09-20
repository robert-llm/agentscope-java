/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.documentation2.lrs.apps;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.examples.documentation2.lrs.OrderFulfillmentExample;
import io.agentscope.extensions.aistio.SessionBridge;
import io.agentscope.extensions.aistio.adapter.AgentScopeAdapter;
import io.agentscope.extensions.aistio.transport.ControlPlaneHttpClient;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.team.TeamClient;
import io.agentscope.harness.agent.team.TeamContext;
import io.agentscope.harness.agent.tool.TeamTool;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fulfillment Lead Agent as an HTTP service.
 *
 * <p>Exposes a REST API for external systems to interact with the order-fulfillment team. The
 * agent registers with AgentScope Service for Team coordination, and simultaneously serves HTTP
 * requests that trigger agent reasoning.
 *
 * <h3>API Endpoints</h3>
 *
 * <ul>
 *   <li>{@code POST /api/chat} — Send a message to the lead agent, get a synchronous response
 *   <li>{@code GET /health} — Health check
 * </ul>
 *
 * <h3>Example</h3>
 *
 * <pre>
 * curl -X POST http://localhost:18096/api/chat \
 *   -H "Content-Type: application/json" \
 *   -d '{"message": "Investigate order O-1001, customer wants delivery by 2026-09-15"}'
 * </pre>
 *
 * <p><b>Environment variables:</b>
 *
 * <ul>
 *   <li>{@code DASHSCOPE_API_KEY} (required) — DashScope API key
 *   <li>{@code AISTIO_CONTROL_PLANE_HTTP} (default: http://localhost:8081) — Service URL
 *   <li>{@code BUILDER_INTERNAL_TOKEN} — Service internal token
 *   <li>{@code AISTIO_CONTRACT_PORT} (default: 18091) — Contract HTTP server port
 *   <li>{@code API_PORT} (default: 18096) — Public HTTP API port
 *   <li>{@code TEAM_NAME} (default: OrderFulfillmentTeam) — Team name for coordination
 * </ul>
 */
public class FulfillmentLeadApp {

    private static final Logger log = LoggerFactory.getLogger(FulfillmentLeadApp.class);
    private static final String AGENT_KEY = "fulfillment-lead";
    private static final int CONTRACT_PORT = 18091;
    private static final int API_PORT =
            Integer.parseInt(OrderFulfillmentExample.envOr("API_PORT", "18096"));
    private static final String TEAM_NAME =
            System.getenv("TEAM_NAME") != null
                    ? System.getenv("TEAM_NAME")
                    : "OrderFulfillmentTeam";

    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        log.info("Starting {} ...", AGENT_KEY);

        // 0. Verify DashScope API key
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            log.error("DASHSCOPE_API_KEY is not set! Set it in IDEA Run Configuration.");
            System.exit(1);
        }
        log.info("DASHSCOPE_API_KEY is set (length={})", apiKey.length());

        // 1. Load shared business data
        var dataStore = OrderFulfillmentExample.loadDataStore();
        var example = new OrderFulfillmentExample(dataStore);

        // 2. Create adapter (provides observation middleware)
        AgentScopeAdapter adapter = new AgentScopeAdapter();

        // 3. Build the agent with middleware
        HarnessAgent agent = example.buildFulfillmentLeadAgent(adapter);

        // 4. Create TeamClient for team coordination via control plane
        String controlPlaneHttp =
                OrderFulfillmentExample.envOr("AISTIO_CONTROL_PLANE_HTTP", "http://localhost:8081");
        String internalToken =
                OrderFulfillmentExample.envOr(
                        "BUILDER_INTERNAL_TOKEN", OrderFulfillmentExample.DEFAULT_INTERNAL_TOKEN);
        ControlPlaneHttpClient httpClient =
                new ControlPlaneHttpClient(controlPlaneHttp, internalToken);
        TeamClient teamClient =
                new io.agentscope.extensions.aistio.store.ControlPlaneTeamClient(httpClient);

        // 5. Register with Service (TeamClient enables team coordination)
        SessionBridge bridge =
                OrderFulfillmentExample.registerWithService(
                        agent, adapter, AGENT_KEY, teamClient, CONTRACT_PORT);

        // 6. Pre-register team tools for HTTP API coordination
        setupTeamTools(agent, teamClient);

        // 7. Test model connectivity
        log.info("Testing model connectivity (dashscope:qwen-plus)...");
        try {
            Msg testMsg = Msg.builder().role(MsgRole.USER).textContent("Hi").build();
            RuntimeContext testRc = RuntimeContext.builder().sessionId("startup-test").build();
            Msg testResp = agent.call(testMsg, testRc).block(Duration.ofSeconds(60));
            if (testResp != null) {
                String preview = testResp.getTextContent();
                if (preview != null && preview.length() > 80) {
                    preview = preview.substring(0, 80) + "...";
                }
                log.info("Model OK: {}", preview);
            } else {
                log.warn("Model returned null response");
            }
        } catch (Exception e) {
            log.error("Model test FAILED: {} — agent will not work!", e.getMessage());
        }

        // 8. Start public HTTP API server
        HttpServer apiServer = startApiServer(agent);

        log.info("============================================");
        log.info("{} is running.", AGENT_KEY);
        log.info("  API endpoint : http://localhost:{}/api/chat", API_PORT);
        log.info("  Health check : http://localhost:{}/health", API_PORT);
        log.info("  Service console: http://localhost:8080");
        log.info("============================================");
        log.info("Press Ctrl+C to stop.");

        // 7. Keep alive until shutdown
        final HttpServer finalApiServer = apiServer;
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    log.info("Shutting down {} ...", AGENT_KEY);
                                    finalApiServer.stop(0);
                                    bridge.close();
                                }));

        Thread.currentThread().join();
    }

    /** Starts the public HTTP API server. */
    private static HttpServer startApiServer(HarnessAgent agent) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", API_PORT), 0);
        AtomicInteger sessionCounter = new AtomicInteger(0);

        // POST /api/chat — synchronous chat endpoint
        server.createContext(
                "/api/chat",
                exchange -> {
                    if (!"POST".equals(exchange.getRequestMethod())) {
                        sendJson(exchange, 405, Map.of("error", "Method not allowed"));
                        return;
                    }
                    try {
                        String body = readBody(exchange);
                        Map<String, Object> request = parseJson(body);
                        String message = (String) request.get("message");
                        if (message == null || message.isBlank()) {
                            sendJson(exchange, 400, Map.of("error", "message is required"));
                            return;
                        }

                        log.info(">>> [API Request] {}", message);

                        // Build request with team coordination context
                        String teamHint =
                                "\n\n[Team Context] You are lead of team '"
                                        + TEAM_NAME
                                        + "'. Use the `team` tool to coordinate:"
                                        + " createTask, assignTask, sendMessage,"
                                        + " listTasks, listMembers.";
                        Msg input =
                                Msg.builder()
                                        .role(MsgRole.USER)
                                        .textContent(message + teamHint)
                                        .build();
                        String sessionId = "api-session-" + sessionCounter.incrementAndGet();
                        RuntimeContext rc = RuntimeContext.builder().sessionId(sessionId).build();

                        log.info("Calling agent (session={})...", sessionId);
                        long t0 = System.currentTimeMillis();

                        Msg response = agent.call(input, rc).block(Duration.ofMinutes(2));

                        long elapsed = System.currentTimeMillis() - t0;
                        String reply = response != null ? response.getTextContent() : "";
                        log.info("<<< [API Response] ({}ms) {}", elapsed, reply);

                        sendJson(exchange, 200, Map.of("reply", reply, "sessionId", sessionId));
                    } catch (Exception e) {
                        Throwable cause = e.getCause();
                        if (cause instanceof java.util.concurrent.TimeoutException
                                || e.getMessage() != null && e.getMessage().contains("Timeout")) {
                            log.error("API chat timed out (2 min)");
                            sendJson(exchange, 504, Map.of("error", "Agent timed out (2 min)"));
                            return;
                        }
                        log.error("API chat failed", e);
                        sendJson(exchange, 500, Map.of("error", e.getMessage()));
                    }
                });

        // GET /health — health check
        server.createContext(
                "/health",
                exchange -> {
                    if (!"GET".equals(exchange.getRequestMethod())) {
                        sendJson(exchange, 405, Map.of("error", "Method not allowed"));
                        return;
                    }
                    sendJson(exchange, 200, Map.of("status", "ok", "agent", AGENT_KEY));
                });

        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        server.start();
        log.info("HTTP API server started on port {}", API_PORT);
        return server;
    }

    /**
     * Pre-registers TeamTool on the agent's toolkit so HTTP requests can immediately use team
     * coordination (createTask, assignTask, sendMessage, etc.) without waiting for team_join.
     */
    private static void setupTeamTools(HarnessAgent agent, TeamClient teamClient) {
        TeamContext ctx =
                new TeamContext(
                        TEAM_NAME,
                        "default",
                        "Coordinate order-fulfillment investigation",
                        "lead",
                        true,
                        List.of(),
                        List.of());
        TeamTool teamTool = new TeamTool(teamClient, ctx);
        var toolkit = agent.getToolkit();
        if (toolkit == null) {
            log.error("Agent toolkit is null! Team tools cannot be registered.");
            return;
        }

        // Log tools before registration
        log.info(
                "Tools before registration: {}",
                toolkit.getToolSchemas().stream().map(t -> t.getName()).toList());

        toolkit.registerTool(teamTool);

        // Log tools after registration
        log.info(
                "Tools after registration: {}",
                toolkit.getToolSchemas().stream().map(t -> t.getName()).toList());
        log.info("Team tools pre-registered (team={}, role=lead)", TEAM_NAME);
    }

    // ─── HTTP helpers ───

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseJson(String body) throws IOException {
        return JSON.readValue(body, Map.class);
    }

    private static void sendJson(HttpExchange exchange, int statusCode, Map<String, Object> data)
            throws IOException {
        String json = JSON.writeValueAsString(data);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
