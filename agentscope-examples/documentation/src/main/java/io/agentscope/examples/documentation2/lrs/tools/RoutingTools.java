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
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEventEmitter;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.extensions.aistio.transport.ControlPlaneHttpClient;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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
    private final TeamResultProcessor resultProcessor;

    public RoutingTools(ControlPlaneHttpClient httpClient, String namespace) {
        this(httpClient, namespace, null);
    }

    public RoutingTools(
            ControlPlaneHttpClient httpClient,
            String namespace,
            TeamResultProcessor resultProcessor) {
        this.httpClient = httpClient;
        this.namespace = namespace;
        this.resultProcessor = resultProcessor;
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
                    Route a task to a specific team and wait for the result. \
                    The team's lead agent picks up and coordinates the task among its members. \
                    This tool blocks until the task completes (max 5 minutes) and returns \
                    the processed result.

                    stream_mode controls event visibility:
                    - 'transparent' (default): forward team discussion to the user in real-time.
                    - 'summary': suppress team events; only the final result is returned.\
                    """)
    public Mono<String> routeTask(
            @ToolParam(name = "teamName", description = "The target team name") String teamName,
            @ToolParam(name = "subject", description = "Short subject/title of the task")
                    String subject,
            @ToolParam(name = "description", description = "Detailed task description")
                    String description,
            @ToolParam(
                            name = "stream_mode",
                            required = false,
                            description =
                                    "transparent (default) = forward team events to user;"
                                            + " summary = suppress events, return result only")
                    String streamMode) {
        if (teamName == null || teamName.isBlank()) {
            return Mono.just("error: teamName must not be blank");
        }
        if (subject == null || subject.isBlank()) {
            return Mono.just("error: subject must not be blank");
        }

        boolean forwardEvents =
                streamMode == null
                        || streamMode.isBlank()
                        || "transparent".equalsIgnoreCase(streamMode);

        return Mono.deferContextual(
                ctx -> {
                    Optional<AgentEventEmitter> emitterOpt =
                            forwardEvents ? AgentEventEmitter.fromContext(ctx) : Optional.empty();

                    return Mono.fromCallable(
                                    () ->
                                            doRouteTask(
                                                    teamName,
                                                    subject,
                                                    description,
                                                    emitterOpt.orElse(null),
                                                    forwardEvents))
                            .subscribeOn(Schedulers.boundedElastic());
                });
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

    // ---- route_task helpers ----------------------------------------------------

    private static final long POLL_INTERVAL_MS = 2_000L;
    private static final long MAX_WAIT_MS = 5 * 60 * 1_000L;

    private String doRouteTask(
            String teamName,
            String subject,
            String description,
            AgentEventEmitter emitter,
            boolean forwardEvents) {
        try {
            // 1. Submit task to control plane
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
            if (resp.status() != 200 && resp.status() != 201) {
                return "error: failed to create task, HTTP " + resp.status();
            }

            JsonNode respBody = MAPPER.readTree(resp.body());
            String taskId = respBody.path("taskId").asText("");
            if (taskId.isEmpty()) {
                return "error: no taskId in response body: " + resp.body();
            }
            log.info("Task routed to team '{}': taskId={}, subject={}", teamName, taskId, subject);

            // 2. Setup event forwarding for transparent mode
            String replyId = UUID.randomUUID().toString().replace("-", "");
            String sourcePath = "router/" + teamName;
            AtomicLong lastMsgId = new AtomicLong(0);

            if (emitter != null) {
                emitter.emit(new AgentStartEvent(taskId, replyId, teamName).withSource(sourcePath));
            }

            // 3. Poll for completion (with event forwarding in transparent mode)
            long deadline = System.currentTimeMillis() + MAX_WAIT_MS;
            boolean completed = false;
            while (System.currentTimeMillis() < deadline) {
                String state = checkTaskState(teamName, taskId);
                if (isTerminalState(state)) {
                    completed = true;
                    break;
                }

                // Forward new team messages in transparent mode
                if (emitter != null) {
                    forwardNewMessages(teamName, lastMsgId, emitter, replyId, sourcePath);
                }

                Thread.sleep(POLL_INTERVAL_MS);
            }

            // 4. Final flush of messages + AgentEnd event
            if (emitter != null) {
                forwardNewMessages(teamName, lastMsgId, emitter, replyId, sourcePath);
                emitter.emit(new AgentEndEvent(replyId).withSource(sourcePath));
            }

            // 5. Fetch final result
            String rawResult = fetchTaskResult(teamName, taskId);

            // 6. Handle timeout: prepend warning when the task did not reach a terminal state
            if (!completed) {
                log.warn(
                        "route_task timed out after {}ms: team='{}', taskId={}",
                        MAX_WAIT_MS,
                        teamName,
                        taskId);
                rawResult =
                        "[超时] 等待团队 '"
                                + teamName
                                + "' 处理超过 "
                                + (MAX_WAIT_MS / 1000 / 60)
                                + " 分钟，任务可能仍在进行中。"
                                + "当前状态: "
                                + rawResult;
            }

            // 7. Apply result processor (always applied in both modes)
            String processed =
                    resultProcessor != null
                            ? resultProcessor.process(teamName, subject, rawResult)
                            : rawResult;

            return formatResult(teamName, taskId, processed);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "error: interrupted while waiting for task result";
        } catch (Exception e) {
            log.error("route_task failed", e);
            return "error: " + e.getMessage();
        }
    }

    private String checkTaskState(String teamName, String taskId) {
        try {
            String path =
                    "/api/v1/teams/"
                            + java.net.URLEncoder.encode(
                                    teamName, java.nio.charset.StandardCharsets.UTF_8)
                            + "/tasks?namespace="
                            + namespace;
            ControlPlaneHttpClient.Response resp = httpClient.send("GET", path, null);
            if (resp.status() != 200) {
                return "unknown";
            }
            JsonNode root = MAPPER.readTree(resp.body());
            JsonNode tasks = root.path("tasks");
            for (int i = 0; i < tasks.size(); i++) {
                JsonNode task = tasks.get(i);
                if (taskId.equals(task.path("taskId").asText(""))) {
                    return task.path("state").asText("");
                }
            }
            return "unknown";
        } catch (Exception e) {
            log.warn("checkTaskState failed: {}", e.getMessage());
            return "unknown";
        }
    }

    private boolean isTerminalState(String state) {
        return "completed".equalsIgnoreCase(state)
                || "failed".equalsIgnoreCase(state)
                || "cancelled".equalsIgnoreCase(state);
    }

    private void forwardNewMessages(
            String teamName,
            AtomicLong lastMsgId,
            AgentEventEmitter emitter,
            String replyId,
            String sourcePath) {
        try {
            String path =
                    "/api/v1/teams/"
                            + java.net.URLEncoder.encode(
                                    teamName, java.nio.charset.StandardCharsets.UTF_8)
                            + "/events?namespace="
                            + namespace
                            + "&after="
                            + lastMsgId.get();
            ControlPlaneHttpClient.Response resp = httpClient.send("GET", path, null);
            if (resp.status() != 200) {
                return;
            }
            JsonNode root = MAPPER.readTree(resp.body());
            JsonNode events = root.path("events");
            for (int i = 0; i < events.size(); i++) {
                JsonNode event = events.get(i);
                long msgId = event.path("id").asLong(0);
                if (msgId > lastMsgId.get()) {
                    lastMsgId.set(msgId);
                }
                String from = event.path("from").asText("");
                String body = event.path("body").asText("");
                if (!body.isEmpty()) {
                    String text = "[" + from + "] " + body + "\n";
                    emitter.emit(
                            new TextBlockDeltaEvent(replyId, "team-msg", text)
                                    .withSource(sourcePath));
                }
            }
        } catch (Exception e) {
            log.debug("forwardNewMessages failed: {}", e.getMessage());
        }
    }

    private String fetchTaskResult(String teamName, String taskId) {
        try {
            String path =
                    "/api/v1/teams/"
                            + java.net.URLEncoder.encode(
                                    teamName, java.nio.charset.StandardCharsets.UTF_8)
                            + "/tasks?namespace="
                            + namespace;
            ControlPlaneHttpClient.Response resp = httpClient.send("GET", path, null);
            if (resp.status() != 200) {
                return "error: could not fetch result (HTTP " + resp.status() + ")";
            }
            JsonNode root = MAPPER.readTree(resp.body());
            JsonNode tasks = root.path("tasks");
            for (int i = 0; i < tasks.size(); i++) {
                JsonNode task = tasks.get(i);
                if (taskId.equals(task.path("taskId").asText(""))) {
                    String state = task.path("state").asText("");
                    String result = task.path("result").asText("");
                    if (result.isEmpty()) {
                        return "task state: " + state + ", no result text available";
                    }
                    return result;
                }
            }
            return "error: task not found after completion";
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    private String formatResult(String teamName, String taskId, String result) {
        StringBuilder sb = new StringBuilder();
        sb.append("team: ").append(teamName).append("\n");
        sb.append("task_id: ").append(taskId).append("\n");
        sb.append("result:\n").append(result);
        return sb.toString();
    }
}
