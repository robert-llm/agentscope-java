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
package io.agentscope.examples.documentation2.lrs.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.extensions.aistio.transport.ControlPlaneHttpClient;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Routing tools for the Router Agent.
 *
 * <p>These tools allow the Router Agent to discover available teams and route tasks to them
 * via the control plane API, without being a member of any team.
 *
 * <p>Unlike TeamTool (which requires team membership and TeamContext), RoutingTools
 * calls the control plane REST API directly.
 */
public final class RoutingTools {

    private static final Logger log = LoggerFactory.getLogger(RoutingTools.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ControlPlaneHttpClient httpClient;
    private final String namespace;

    public RoutingTools(ControlPlaneHttpClient httpClient, String namespace) {
        this.httpClient = httpClient;
        this.namespace = namespace;
    }

    @Tool(
            name = "list_teams",
            readOnly = true,
            description =
                    """
                    List all available teams on the control plane.
                    Returns team names, objectives, member counts, and phases.
                    Use this to discover which teams are available for routing.\
                    """)
    public String listTeams() {
        try {
            ControlPlaneHttpClient.Response resp =
                    httpClient.send("GET", "/api/v1/teams?namespace=" + namespace, null);
            if (resp.status() != 200) {
                return "error: failed to list teams, HTTP " + resp.status();
            }

            JsonNode root = MAPPER.readTree(resp.body());
            JsonNode items = root.path("items");

            ObjectNode result = MAPPER.createObjectNode();
            result.put("namespace", namespace);
            result.put("count", items.size());

            for (int i = 0; i < items.size(); i++) {
                JsonNode item = items.get(i);
                ObjectNode team = (ObjectNode) items.get(i);
                // Simplify output: keep only key fields
                ObjectNode simplified = MAPPER.createObjectNode();
                simplified.put("name", item.path("name").asText());
                simplified.put("objective", item.path("objective").asText(""));
                simplified.put("phase", item.path("phase").asText(""));
                simplified.put("memberCount", item.path("memberCount").asInt(0));
                simplified.put("leadRef", item.path("leadRef").asText(""));
                result.set("team_" + i, simplified);
            }

            return result.toString();
        } catch (Exception e) {
            log.error("list_teams failed", e);
            return "error: " + e.getMessage();
        }
    }

    @Tool(
            name = "route_task",
            description =
                    """
                    Route a task to a specific team by creating a task on the control plane.
                    The team's lead agent will pick up and coordinate the task among its members.
                    Use this after identifying which team is best suited for the request.\
                    """)
    public String routeTask(
            @ToolParam(name = "teamName", description = "The target team name") String teamName,
            @ToolParam(name = "subject", description = "Short subject/title of the task")
                    String subject,
            @ToolParam(name = "description", description = "Detailed task description")
                    String description) {
        if (teamName == null || teamName.isBlank()) {
            return "error: teamName must not be blank";
        }
        if (subject == null || subject.isBlank()) {
            return "error: subject must not be blank";
        }

        try {
            Map<String, Object> taskBody =
                    Map.of(
                            "subject", subject,
                            "description", description != null ? description : "",
                            "namespace", namespace);

            String path =
                    "/api/v1/teams/"
                            + java.net.URLEncoder.encode(
                                    teamName, java.nio.charset.StandardCharsets.UTF_8)
                            + "/tasks?namespace="
                            + namespace;

            ControlPlaneHttpClient.Response resp = httpClient.send("POST", path, taskBody);

            ObjectNode result = MAPPER.createObjectNode();
            if (resp.status() == 200 || resp.status() == 201) {
                result.put("status", "created");
                result.put("team", teamName);
                result.put("subject", subject);
                log.info("Task routed to team '{}': {}", teamName, subject);
            } else {
                result.put("status", "failed");
                result.put("team", teamName);
                result.put("httpStatus", resp.status());
                result.put("detail", resp.body());
                log.warn("Failed to route task to team '{}': HTTP {}", teamName, resp.status());
            }
            return result.toString();
        } catch (Exception e) {
            log.error("route_task failed", e);
            return "error: " + e.getMessage();
        }
    }

    @Tool(
            name = "list_team_members",
            readOnly = true,
            description =
                    """
                    List members of a specific team.
                    Returns agent names, agent references, and their current phase.
                    Use this to understand a team's capabilities before routing.\
                    """)
    public String listTeamMembers(
            @ToolParam(name = "teamName", description = "The team name") String teamName) {
        if (teamName == null || teamName.isBlank()) {
            return "error: teamName must not be blank";
        }

        try {
            String path =
                    "/api/v1/teams/"
                            + java.net.URLEncoder.encode(
                                    teamName, java.nio.charset.StandardCharsets.UTF_8)
                            + "/members?namespace="
                            + namespace;

            ControlPlaneHttpClient.Response resp = httpClient.send("GET", path, null);
            if (resp.status() != 200) {
                return "error: failed to list members for team '"
                        + teamName
                        + "', HTTP "
                        + resp.status();
            }

            JsonNode root = MAPPER.readTree(resp.body());
            JsonNode members = root.path("members");

            ObjectNode result = MAPPER.createObjectNode();
            result.put("team", teamName);
            result.put("count", members.size());

            for (int i = 0; i < members.size(); i++) {
                ObjectNode member = MAPPER.createObjectNode();
                member.put("name", members.get(i).path("name").asText());
                member.put("agentRef", members.get(i).path("agentRef").asText());
                member.put("phase", members.get(i).path("phase").asText("Working"));
                result.set("member_" + i, member);
            }

            return result.toString();
        } catch (Exception e) {
            log.error("list_team_members failed", e);
            return "error: " + e.getMessage();
        }
    }
}
