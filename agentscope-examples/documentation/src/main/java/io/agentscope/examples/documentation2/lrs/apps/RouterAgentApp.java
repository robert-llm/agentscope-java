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

import io.agentscope.core.tool.Toolkit;
import io.agentscope.examples.documentation2.lrs.tools.RoutingTools;
import io.agentscope.examples.documentation2.lrs.tools.TeamResultProcessor;
import io.agentscope.extensions.aistio.Aistio;
import io.agentscope.extensions.aistio.AistioConfig;
import io.agentscope.extensions.aistio.SessionBridge;
import io.agentscope.extensions.aistio.adapter.AgentScopeAdapter;
import io.agentscope.extensions.aistio.transport.ControlPlaneHttpClient;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Paths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Router Agent — standalone, generic entry point for all user requests.
 *
 * <p>This application is completely independent of any specific business domain.
 * It discovers available teams via the control plane API and routes tasks to them.
 *
 * <h3>Architecture</h3>
 *
 * <pre>{@code
 * Frontend / Business System
 *         │
 *         ▼
 * ┌─────────────────────────────────────────┐
 * │  RouterAgentApp (this app)              │
 * │  - Spring Boot HTTP API (port 18096)    │
 * │  - Router Agent (contract port 18090)   │
 * │  - RoutingTools (control plane API)     │
 * │  - No business logic, no team membership│
 * └────────────────┬────────────────────────┘
 *                  │ routes via control plane API
 *         ┌────────┼────────┐
 *         ▼        ▼        ▼
 *    ┌────────┐┌────────┐┌────────┐
 *    │ Team A ││ Team B ││ Team C │
 *    │(Lead)  ││(Lead)  ││(Lead)  │
 *    └────────┘└────────┘└────────┘
 * }</pre>
 *
 * <h3>Key Design Decisions</h3>
 * <ul>
 *   <li>No dependency on OrderFulfillmentExample — this is a generic router
 *   <li>No team membership — uses control plane API to discover and route
 *   <li>No HarnessTeamSessionStarter — doesn't join any team
 *   <li>RoutingTools registered directly in agent toolkit
 * </ul>
 *
 * <p><b>Environment variables:</b>
 * <ul>
 *   <li>{@code DASHSCOPE_API_KEY} (required) — DashScope API key
 *   <li>{@code AISTIO_CONTROL_PLANE_HTTP} (default: http://localhost:8081) — Service URL
 *   <li>{@code BUILDER_INTERNAL_TOKEN} (default: local-dev-internal-token-at-least-32chars)
 *   <li>{@code AISTIO_CONTRACT_PORT} (default: 18090) — Contract HTTP server port
 *   <li>{@code SERVER_PORT} (default: 18096) — HTTP API port (Spring Boot standard)
 * </ul>
 */
@SpringBootApplication
public class RouterAgentApp {

    private static final Logger log = LoggerFactory.getLogger(RouterAgentApp.class);
    private static final String AGENT_KEY = "router-agent";
    private static final int DEFAULT_CONTRACT_PORT = 18090;
    private static final String DEFAULT_INTERNAL_TOKEN =
            "local-dev-internal-token-at-least-32chars";

    public static void main(String[] args) {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            log.error("DASHSCOPE_API_KEY is not set! Set it in IDEA Run Configuration.");
            System.exit(1);
        }
        log.info("DASHSCOPE_API_KEY is set (length={})", apiKey.length());

        SpringApplication.run(RouterAgentApp.class, args);
    }

    @Bean
    public HarnessAgent routerAgent() throws Exception {
        log.info("Building router-agent...");

        // Build agent directly — no dependency on OrderFulfillmentExample
        Toolkit toolkit = new Toolkit();

        // Register routing tools (uses control plane API, not team membership)
        String controlPlaneHttp = envOr("AISTIO_CONTROL_PLANE_HTTP", "http://localhost:8081");
        String internalToken = envOr("BUILDER_INTERNAL_TOKEN", DEFAULT_INTERNAL_TOKEN);
        ControlPlaneHttpClient httpClient =
                new ControlPlaneHttpClient(controlPlaneHttp, internalToken);
        String namespace = "default";
        // Custom result processor: formats team output for the Router LLM
        TeamResultProcessor resultProcessor =
                (teamName, subject, rawResult) ->
                        String.format(
                                """
                                团队: %s
                                任务: %s
                                调查结果:
                                %s

                                请基于以上调查结果，为用户生成简洁清晰的中文汇总。\
                                """,
                                teamName, subject, rawResult);

        toolkit.registerTool(new RoutingTools(httpClient, namespace, resultProcessor));

        AgentScopeAdapter adapter = new AgentScopeAdapter();

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name(AGENT_KEY)
                        .description(
                                "Routes user requests to the appropriate team. Analyzes intent and"
                                        + " delegates to teams via control plane API.")
                        .sysPrompt(routerPrompt())
                        .model("dashscope:qwen-plus")
                        .toolkit(toolkit)
                        .workspace(agentWorkspace(AGENT_KEY))
                        .middleware(adapter.middleware())
                        .maxIters(30)
                        .disableFilesystemTools()
                        .disableShellTool()
                        .disableSubagents()
                        .disableMemoryTools()
                        .build();

        // Register with AgentScope Service — no team session starter
        int contractPort =
                Integer.parseInt(
                        envOr("AISTIO_CONTRACT_PORT", String.valueOf(DEFAULT_CONTRACT_PORT)));

        String instanceId = uniqueInstanceId(AGENT_KEY);

        AistioConfig config =
                AistioConfig.builder(AGENT_KEY)
                        .controlPlaneHttp(controlPlaneHttp)
                        .internalToken(internalToken)
                        .namespace(namespace)
                        .instanceId(instanceId)
                        .enableEvents(true)
                        .contractHttpPort(contractPort)
                        .startGrpc(false)
                        .startHttp(true)
                        .build();

        // Note: No adapter.setTeamSessionStarter() — Router Agent doesn't join teams
        SessionBridge bridge = Aistio.instrument(agent, config, adapter);

        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    log.info("Shutting down {} ...", AGENT_KEY);
                                    bridge.close();
                                }));

        log.info("============================================");
        log.info("{} is running.", AGENT_KEY);
        log.info("  Service console: http://localhost:8080");
        log.info("============================================");

        return agent;
    }

    @Bean
    public CommandLineRunner startupLogger() {
        return args -> {
            String port = envOr("SERVER_PORT", "18096");
            log.info("HTTP API server started on port {}", port);
            log.info("  API endpoint : http://localhost:{}/api/chat", port);
            log.info("  SSE stream  : http://localhost:{}/api/chat/stream", port);
            log.info("  Health check : http://localhost:{}/health", port);
        };
    }

    // ---- Prompt ----------------------------------------------------------------

    private static String routerPrompt() {
        return """
        You are the Router Agent — the entry point for all user requests.

        ## Language

        Always respond in Chinese (简体中文). Match the user's language for all outputs.

        ## Your Role

        You receive user requests and route them to the appropriate team.
        You do NOT execute business tasks yourself — you delegate to teams.
        The `route_task` tool waits for the team to finish and returns the result.

        ## Routing Workflow

        1. Use `list_teams` to discover available teams and their objectives.
        2. Use `list_team_members` to understand a team's capabilities.
        3. Use `route_task` to create a task in the appropriate team.
           This tool blocks until the task completes (max 5 minutes) and returns
           the processed result directly — no polling needed.

        ## stream_mode Parameter

        The `route_task` tool accepts an optional `stream_mode` parameter:
        - `transparent` (default): Team discussion is forwarded to the user in real-time.
          The user sees the team's reasoning, tool calls, and messages as they happen.
        - `summary`: Team events are suppressed. The user only sees your final summary.

        Choose `transparent` when the user benefits from seeing the team's process.
        Choose `summary` when only the conclusion matters.

        ## Response Format

        Your final response to the user should include:
        1. Which team handled the task
        2. A clear summary of the team's findings in Chinese
        3. Actionable next steps if applicable

        ## Rules

        - Do NOT use filesystem tools, shell, or memory tools — they are disabled.
        - Do NOT try to execute tasks yourself — always route to teams.
        - If you cannot determine which team to route to, ask the user for clarification.
        - The `route_task` result already includes the team's output — summarize it for the user.
        - Use `get_team_messages` only if you need additional context not in the result.
        """;
    }

    // ---- Utilities -------------------------------------------------------------

    private static String agentWorkspace(String agentKey) {
        return Paths.get(
                        System.getProperty("user.home"),
                        ".agentscope",
                        "router",
                        agentKey,
                        "workspace")
                .toString();
    }

    private static String uniqueInstanceId(String agentKey) {
        String host;
        try {
            host = java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.net.UnknownHostException e) {
            host = "localhost";
        }
        return host + "-" + agentKey;
    }

    static String envOr(String name, String defaultValue) {
        String v = System.getenv(name);
        return (v != null && !v.isBlank()) ? v : defaultValue;
    }
}
