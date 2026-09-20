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

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.examples.documentation2.lrs.OrderFulfillmentExample;
import io.agentscope.extensions.aistio.SessionBridge;
import io.agentscope.extensions.aistio.adapter.AgentScopeAdapter;
import io.agentscope.extensions.aistio.store.ControlPlaneTeamClient;
import io.agentscope.extensions.aistio.transport.ControlPlaneHttpClient;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.team.TeamClient;
import io.agentscope.harness.agent.team.TeamContext;
import io.agentscope.harness.agent.team.TeamContext.MemberSnapshot;
import io.agentscope.harness.agent.tool.TeamTool;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Fulfillment Lead Agent as a Spring Boot HTTP service with multi-team support.
 *
 * <p>Discovers all teams the agent belongs to at startup, creates a TeamTool for each team, and
 * exposes a REST API for external systems to interact with any team.
 *
 * <h3>API Endpoints</h3>
 *
 * <ul>
 *   <li>{@code POST /api/chat} — Send a message, optionally specifying a team
 *   <li>{@code GET /api/teams} — List all available teams
 *   <li>{@code GET /health} — Health check
 * </ul>
 *
 * <h3>Examples</h3>
 *
 * <pre>
 * # Chat with a specific team
 * curl -X POST http://localhost:18096/api/chat \
 *   -H "Content-Type: application/json" \
 *   -d '{"message": "调查订单 O-1001", "team": "OrderFulfillmentTeam"}'
 *
 * # Chat without specifying team (auto-select)
 * curl -X POST http://localhost:18096/api/chat \
 *   -H "Content-Type: application/json" \
 *   -d '{"message": "调查订单 O-1001"}'
 *
 * # List available teams
 * curl http://localhost:18096/api/teams
 * </pre>
 *
 * <p><b>Environment variables:</b>
 *
 * <ul>
 *   <li>{@code DASHSCOPE_API_KEY} (required) — DashScope API key
 *   <li>{@code AISTIO_CONTROL_PLANE_HTTP} (default: http://localhost:8081) — Service URL
 *   <li>{@code BUILDER_INTERNAL_TOKEN} — Service internal token
 *   <li>{@code AISTIO_CONTRACT_PORT} (default: 18091) — Contract HTTP server port
 *   <li>{@code SERVER_PORT} (default: 18096) — HTTP API port (Spring Boot standard)
 * </ul>
 */
@SpringBootApplication
public class FulfillmentLeadApp {

    private static final Logger log = LoggerFactory.getLogger(FulfillmentLeadApp.class);
    private static final String AGENT_KEY = "fulfillment-lead";
    private static final int CONTRACT_PORT = 18091;

    public static void main(String[] args) {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            log.error("DASHSCOPE_API_KEY is not set! Set it in IDEA Run Configuration.");
            System.exit(1);
        }
        log.info("DASHSCOPE_API_KEY is set (length={})", apiKey.length());

        SpringApplication.run(FulfillmentLeadApp.class, args);
    }

    @Bean
    public HarnessAgent fulfillmentLeadAgent() throws Exception {
        log.info("Building fulfillment-lead agent...");

        var dataStore = OrderFulfillmentExample.loadDataStore();
        var example = new OrderFulfillmentExample(dataStore);
        AgentScopeAdapter adapter = new AgentScopeAdapter();
        HarnessAgent agent = example.buildFulfillmentLeadAgent(adapter);

        String controlPlaneHttp =
                OrderFulfillmentExample.envOr("AISTIO_CONTROL_PLANE_HTTP", "http://localhost:8081");
        String internalToken =
                OrderFulfillmentExample.envOr(
                        "BUILDER_INTERNAL_TOKEN", OrderFulfillmentExample.DEFAULT_INTERNAL_TOKEN);
        ControlPlaneHttpClient httpClient =
                new ControlPlaneHttpClient(controlPlaneHttp, internalToken);
        TeamClient teamClient = new ControlPlaneTeamClient(httpClient);

        SessionBridge bridge =
                OrderFulfillmentExample.registerWithService(
                        agent, adapter, AGENT_KEY, teamClient, CONTRACT_PORT);

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

    /**
     * Discovers all teams from the control plane and creates a TeamTool for each team the agent
     * belongs to. Returns a map of teamName -> TeamTool.
     */
    @Bean
    public Map<String, TeamTool> teamTools() throws Exception {
        return discoverAndCreateTeamTools();
    }

    /**
     * Discovers all teams and creates TeamTools. Can be called at startup or during runtime refresh.
     */
    public Map<String, TeamTool> discoverAndCreateTeamTools() throws Exception {
        String controlPlaneHttp =
                OrderFulfillmentExample.envOr("AISTIO_CONTROL_PLANE_HTTP", "http://localhost:8081");
        String internalToken =
                OrderFulfillmentExample.envOr(
                        "BUILDER_INTERNAL_TOKEN", OrderFulfillmentExample.DEFAULT_INTERNAL_TOKEN);
        ControlPlaneHttpClient httpClient =
                new ControlPlaneHttpClient(controlPlaneHttp, internalToken);
        TeamClient teamClient = new ControlPlaneTeamClient(httpClient);

        String namespace = "default";
        Map<String, TeamTool> tools = new LinkedHashMap<>();

        try {
            ControlPlaneHttpClient.Response resp =
                    httpClient.send("GET", "/api/v1/teams?namespace=" + namespace, null);
            if (resp.status() != 200) {
                log.warn("Failed to list teams: HTTP {} {}", resp.status(), resp.body());
                return tools;
            }

            JsonNode root = ControlPlaneHttpClient.mapper().readTree(resp.body());
            JsonNode items = root.path("items");
            log.info("Found {} team(s) on control plane", items.size());

            for (JsonNode item : items) {
                String teamName = item.path("name").asText();
                String phase = item.path("phase").asText();
                int memberCount = item.path("memberCount").asInt();
                log.info(
                        "  Checking team: {} (phase={}, members={})", teamName, phase, memberCount);

                ControlPlaneHttpClient.Response membersResp =
                        httpClient.send(
                                "GET",
                                "/api/v1/teams/"
                                        + URLEncoder.encode(teamName, StandardCharsets.UTF_8)
                                        + "/members?namespace="
                                        + namespace,
                                null);

                if (membersResp.status() == 200) {
                    JsonNode membersRoot =
                            ControlPlaneHttpClient.mapper().readTree(membersResp.body());
                    boolean isMember = false;
                    boolean isLead = false;
                    List<String> memberNames = new ArrayList<>();

                    for (JsonNode member : membersRoot.path("members")) {
                        String agentRef = member.path("agentRef").asText();
                        String memberName = member.path("name").asText();
                        memberNames.add(memberName);
                        if (AGENT_KEY.equals(agentRef)) {
                            isMember = true;
                            if (AGENT_KEY.equals(item.path("leadRef").asText())) {
                                isLead = true;
                            }
                        }
                    }

                    if (isMember) {
                        List<MemberSnapshot> memberSnapshots = new ArrayList<>();
                        for (JsonNode member : membersRoot.path("members")) {
                            memberSnapshots.add(
                                    new MemberSnapshot(
                                            member.path("name").asText(),
                                            member.path("agentRef").asText(),
                                            member.path("phase").asText("Working")));
                        }

                        TeamContext ctx =
                                new TeamContext(
                                        teamName,
                                        namespace,
                                        item.path("objective").asText(""),
                                        isLead ? "lead" : "worker",
                                        isLead,
                                        memberSnapshots,
                                        List.of());

                        TeamTool teamTool = new TeamTool(teamClient, ctx);
                        tools.put(teamName, teamTool);
                        log.info(
                                "  ✓ Registered TeamTool for: {} (role={}, members={})",
                                teamName,
                                isLead ? "lead" : "worker",
                                memberNames);
                    } else {
                        log.info("  ✗ Agent is not a member of: {}", teamName);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Team discovery failed: {}", e.getMessage());
        }

        log.info("Total TeamTools registered: {}", tools.size());
        return tools;
    }

    /** Stores the team tools map for the controller to access. */
    @Bean
    public TeamToolsRegistry teamToolsRegistry(Map<String, TeamTool> teamTools) {
        return new TeamToolsRegistry(teamTools);
    }

    @Bean
    public CommandLineRunner startupLogger() {
        return args -> {
            String port = OrderFulfillmentExample.envOr("SERVER_PORT", "18096");
            log.info("HTTP API server started on port {}", port);
            log.info("  API endpoint : http://localhost:{}/api/chat", port);
            log.info("  Teams endpoint: http://localhost:{}/api/teams", port);
            log.info("  Health check : http://localhost:{}/health", port);
        };
    }

    /** Simple registry to hold the team tools map with refresh capability. */
    public static class TeamToolsRegistry {
        private volatile Map<String, TeamTool> teamTools;

        public TeamToolsRegistry(Map<String, TeamTool> teamTools) {
            this.teamTools = teamTools;
        }

        public Map<String, TeamTool> getTeamTools() {
            return teamTools;
        }

        public TeamTool getTeamTool(String teamName) {
            return teamTools.get(teamName);
        }

        public List<String> getTeamNames() {
            return new ArrayList<>(teamTools.keySet());
        }

        /** Update the team tools map (called during refresh). */
        public void updateTeamTools(Map<String, TeamTool> newTools) {
            this.teamTools = newTools;
        }
    }
}
