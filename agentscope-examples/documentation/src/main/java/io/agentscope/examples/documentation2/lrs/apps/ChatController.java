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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.tool.TeamTool;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** REST controller for the Fulfillment Lead Agent HTTP API with multi-team support. */
@RestController
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final String AGENT_KEY = "fulfillment-lead";

    @Autowired private HarnessAgent fulfillmentLeadAgent;

    @Autowired private FulfillmentLeadApp.TeamToolsRegistry teamToolsRegistry;

    @Autowired private FulfillmentLeadApp fulfillmentLeadApp;

    private final AtomicInteger sessionCounter = new AtomicInteger(0);

    /**
     * POST /api/chat — Send a message to the agent, optionally specifying a team.
     *
     * <p>Examples:
     *
     * <pre>
     * # With specific team
     * curl -X POST http://localhost:18096/api/chat \
     *   -H "Content-Type: application/json" \
     *   -d '{"message": "调查订单 O-1001", "team": "OrderFulfillmentTeam"}'
     *
     * # Without team (auto-select)
     * curl -X POST http://localhost:18096/api/chat \
     *   -H "Content-Type: application/json" \
     *   -d '{"message": "调查订单 O-1001"}'
     * </pre>
     */
    @PostMapping("/api/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, String> request) {
        String message = request.get("message");
        if (message == null || message.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "message is required"));
        }

        String teamName = request.get("team");
        log.info(">>> [API Request] message={}, team={}", message, teamName);

        try {
            TeamTool selectedTool = null;

            if (teamName != null && !teamName.isBlank()) {
                // Specific team requested
                selectedTool = teamToolsRegistry.getTeamTool(teamName);
                if (selectedTool == null) {
                    List<String> availableTeams = teamToolsRegistry.getTeamNames();
                    return ResponseEntity.badRequest()
                            .body(
                                    Map.of(
                                            "error",
                                            "Unknown team: " + teamName,
                                            "availableTeams",
                                            availableTeams));
                }
                log.info("Using specified team: {}", teamName);
            } else {
                // Auto-select: use the first available team
                Map<String, TeamTool> allTools = teamToolsRegistry.getTeamTools();
                if (!allTools.isEmpty()) {
                    selectedTool = allTools.values().iterator().next();
                    teamName = allTools.keySet().iterator().next();
                    log.info("Auto-selected team: {}", teamName);
                }
            }

            // Dynamically register the TeamTool
            var toolkit = fulfillmentLeadAgent.getToolkit();
            if (selectedTool != null && toolkit != null) {
                toolkit.registerTool(selectedTool);
                log.debug("Registered TeamTool for team: {}", teamName);
            }

            try {
                // Build request with team context
                String teamHint =
                        teamName != null
                                ? "\n\n[Team Context] You are working with team '"
                                        + teamName
                                        + "'. Use the `team` tool to coordinate."
                                : "\n\n"
                                      + "[Team Context] Use the `team` tool to coordinate with your"
                                      + " team.";

                Msg input =
                        Msg.builder().role(MsgRole.USER).textContent(message + teamHint).build();
                String sessionId = "api-session-" + sessionCounter.incrementAndGet();
                RuntimeContext rc = RuntimeContext.builder().sessionId(sessionId).build();

                log.info("Calling agent (session={})...", sessionId);
                long t0 = System.currentTimeMillis();

                Msg response = fulfillmentLeadAgent.call(input, rc).block(Duration.ofMinutes(2));

                long elapsed = System.currentTimeMillis() - t0;
                String reply = response != null ? response.getTextContent() : "";
                log.info("<<< [API Response] ({}ms) {}", elapsed, reply);

                return ResponseEntity.ok(
                        Map.of(
                                "reply",
                                reply,
                                "sessionId",
                                sessionId,
                                "team",
                                teamName != null ? teamName : ""));
            } finally {
                // Always unregister the TeamTool after the call
                if (selectedTool != null && toolkit != null) {
                    toolkit.removeTool("team");
                    log.debug("Unregistered TeamTool for team: {}", teamName);
                }
            }
        } catch (Exception e) {
            Throwable cause = e.getCause();
            if (cause instanceof java.util.concurrent.TimeoutException
                    || e.getMessage() != null && e.getMessage().contains("Timeout")) {
                log.error("API chat timed out (2 min)");
                return ResponseEntity.status(504).body(Map.of("error", "Agent timed out (2 min)"));
            }
            log.error("API chat failed", e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /** GET /api/teams — List all available teams. */
    @GetMapping("/api/teams")
    public ResponseEntity<Map<String, Object>> listTeams() {
        List<String> teams = teamToolsRegistry.getTeamNames();
        return ResponseEntity.ok(Map.of("teams", teams, "count", teams.size()));
    }

    /**
     * POST /api/teams/refresh — Refresh the team list from the control plane.
     *
     * <p>Use this after adding/removing teams in the UI to update the agent's team tools without
     * restarting.
     */
    @PostMapping("/api/teams/refresh")
    public ResponseEntity<Map<String, Object>> refreshTeams() {
        log.info("Refreshing team list from control plane...");
        try {
            Map<String, TeamTool> newTools = fulfillmentLeadApp.discoverAndCreateTeamTools();
            teamToolsRegistry.updateTeamTools(newTools);
            List<String> teams = teamToolsRegistry.getTeamNames();
            log.info("Team refresh complete. Found {} team(s): {}", teams.size(), teams);
            return ResponseEntity.ok(
                    Map.of(
                            "message",
                            "Team list refreshed successfully",
                            "teams",
                            teams,
                            "count",
                            teams.size()));
        } catch (Exception e) {
            log.error("Team refresh failed", e);
            return ResponseEntity.status(500)
                    .body(Map.of("error", "Team refresh failed: " + e.getMessage()));
        }
    }

    /** GET /health — Health check endpoint. */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "ok", "agent", AGENT_KEY));
    }
}
