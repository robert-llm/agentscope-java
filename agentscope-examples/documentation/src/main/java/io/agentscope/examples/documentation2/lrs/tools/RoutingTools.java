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
                // Parse taskId from the response
                try {
                    JsonNode respBody = MAPPER.readTree(resp.body());
                    String taskId = respBody.path("taskId").asText("");
                    if (!taskId.isEmpty()) {
                        result.put("taskId", taskId);
                    }
                } catch (Exception parseEx) {
                    log.debug("Could not parse taskId from response: {}", parseEx.getMessage());
                }
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
            name = "get_task_status",
            readOnly = true,
            description =
                    """
                    Get the status and result of a specific task on a team.
                    Returns the task state (pending, in_progress, completed, failed) and
                    the result text if completed. Use this after route_task to poll for
                    the team's processing result.
                    """)
    public String getTaskStatus(
            @ToolParam(name = "teamName", description = "The team name") String teamName,
            @ToolParam(name = "taskId", description = "The task ID returned by route_task")
                    String taskId) {
        if (teamName == null || teamName.isBlank()) {
            return "error: teamName must not be blank";
        }
        if (taskId == null || taskId.isBlank()) {
            return "error: taskId must not be blank";
        }

        try {
            String path =
                    "/api/v1/teams/"
                            + java.net.URLEncoder.encode(
                                    teamName, java.nio.charset.StandardCharsets.UTF_8)
                            + "/tasks?namespace="
                            + namespace;

            ControlPlaneHttpClient.Response resp = httpClient.send("GET", path, null);
            if (resp.status() != 200) {
                return "error: failed to list tasks for team '"
                        + teamName
                        + "', HTTP "
                        + resp.status();
            }

            JsonNode root = MAPPER.readTree(resp.body());
            JsonNode tasks = root.path("tasks");

            // Find the specific task by taskId
            for (int i = 0; i < tasks.size(); i++) {
                JsonNode task = tasks.get(i);
                if (taskId.equals(task.path("taskId").asText(""))) {
                    ObjectNode result = MAPPER.createObjectNode();
                    result.put("taskId", task.path("taskId").asText());
                    result.put("team", teamName);
                    result.put("subject", task.path("subject").asText(""));
                    result.put("state", task.path("state").asText(""));
                    result.put("owner", task.path("owner").asText(""));
                    String taskResult = task.path("result").asText("");
                    if (!taskResult.isEmpty()) {
                        result.put("result", taskResult);
                    }
                    return result.toString();
                }
            }

            return "error: task '" + taskId + "' not found in team '" + teamName + "'";
        } catch (Exception e) {
            log.error("get_task_status failed", e);
            return "error: " + e.getMessage();
        }
    }

    @Tool(
            name = "get_team_messages",
            readOnly = true,
            description =
                    """
                    Get recent messages exchanged within a team.
                    Returns messages between team members including task assignments,
                    progress reports, and results. Use this to see what the team discussed
                    and concluded about a routed task.
                    """)
    public String getTeamMessages(
            @ToolParam(name = "teamName", description = "The team name") String teamName) {
        if (teamName == null || teamName.isBlank()) {
            return "error: teamName must not be blank";
        }

        try {
            String path =
                    "/api/v1/teams/"
                            + java.net.URLEncoder.encode(
                                    teamName, java.nio.charset.StandardCharsets.UTF_8)
                            + "/messages?namespace="
                            + namespace;

            ControlPlaneHttpClient.Response resp = httpClient.send("GET", path, null);
            if (resp.status() != 200) {
                return "error: failed to list messages for team '"
                        + teamName
                        + "', HTTP "
                        + resp.status();
            }

            JsonNode root = MAPPER.readTree(resp.body());
            JsonNode messages = root.path("messages");
            if (messages.isMissingNode()) {
                messages = root.path("items");
            }

            ObjectNode result = MAPPER.createObjectNode();
            result.put("team", teamName);
            result.put("count", messages.size());

            // Return last 20 messages to avoid overwhelming output
            int start = Math.max(0, messages.size() - 20);
            for (int i = start; i < messages.size(); i++) {
                JsonNode msg = messages.get(i);
                ObjectNode simplified = MAPPER.createObjectNode();
                simplified.put("from", msg.path("from").asText(""));
                simplified.put("to", msg.path("to").asText(""));
                simplified.put("body", msg.path("body").asText(""));
                simplified.put("timestamp", msg.path("createdAt").asText(""));
                result.set("message_" + (i - start), simplified);
            }

            return result.toString();
        } catch (Exception e) {
            log.error("get_team_messages failed", e);
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
