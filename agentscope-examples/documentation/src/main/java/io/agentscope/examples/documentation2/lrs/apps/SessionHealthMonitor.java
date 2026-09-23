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

import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.aistio.SessionBridge;
import io.agentscope.extensions.aistio.transport.ControlPlaneHttpClient;
import io.agentscope.harness.agent.HarnessAgent;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Session 健康监控器：定期检测 Lead 的 team 工具是否可用，丢失时主动请求控制面恢复。
 *
 * <p>工作原理：
 * <ol>
 *   <li>每 6 秒检查一次 agent toolkit 中是否存在 "team" 工具</li>
 *   <li>如果 team 工具丢失（session 被归档），尝试通过控制面 API 触发 team_join</li>
 *   <li>恢复成功后继续监控，失败则记录日志并提示手动干预</li>
 * </ol>
 *
 * <p>设计考虑：
 * <ul>
 *   <li>使用 daemon 线程，不阻止 JVM 关闭</li>
 *   <li>使用 AtomicBoolean 防止并发恢复请求</li>
 *   <li>恢复失败时提供清晰的手动干预指引</li>
 * </ul>
 */
public class SessionHealthMonitor {

    private static final Logger log = LoggerFactory.getLogger(SessionHealthMonitor.class);

    private final SessionBridge bridge;
    private final String teamName;
    private final String namespace;
    private final HarnessAgent agent;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean recovering = new AtomicBoolean(false);

    /** 健康检查间隔：6 秒 */
    private static final long HEALTH_CHECK_INTERVAL_MS = 6_000;

    /** 恢复冷却时间：30 秒（避免频繁重试） */
    private static final long RECOVERY_COOLDOWN_MS = 30_000;

    /** 连续失败次数（用于降低日志频率） */
    private int consecutiveFailures = 0;

    /** 连续成功后重置日志级别 */
    private static final int LOG_SUPPRESS_THRESHOLD = 3;

    private volatile long lastRecoveryAttemptMs = 0;

    public SessionHealthMonitor(
            SessionBridge bridge, String teamName, String namespace, HarnessAgent agent) {
        this.bridge = bridge;
        this.teamName = teamName;
        this.namespace = namespace;
        this.agent = agent;
        this.scheduler =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread t = new Thread(r, "session-health-monitor");
                            t.setDaemon(true);
                            return t;
                        });
    }

    /**
     * 启动监控器
     */
    public void start() {
        scheduler.scheduleAtFixedRate(
                this::checkSessionHealth, 0, HEALTH_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
        log.info(
                "Session health monitor started (interval={}ms, team={}/{})",
                HEALTH_CHECK_INTERVAL_MS,
                namespace,
                teamName);
    }

    /**
     * 停止监控器
     */
    public void stop() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("Session health monitor stopped");
    }

    /**
     * 检查 session 健康状态
     */
    private void checkSessionHealth() {
        try {
            boolean hasTeamTools = hasTeamToolsRegistered();

            if (!hasTeamTools) {
                consecutiveFailures++;
                // 前 3 次每次打印，之后每 10 次打印一次，避免刷屏
                if (consecutiveFailures <= LOG_SUPPRESS_THRESHOLD
                        || consecutiveFailures % 10 == 0) {
                    log.warn(
                            "Team tools not registered (session may be archived, failures={})",
                            consecutiveFailures);
                }
                attemptRecovery();
            } else {
                if (consecutiveFailures > 0) {
                    log.info(
                            "Session health recovered: team tools available (after {} failures)",
                            consecutiveFailures);
                } else {
                    log.debug("Session health check passed: team tools available");
                }
                consecutiveFailures = 0;
            }
        } catch (Exception e) {
            log.error("Session health check failed", e);
        }
    }

    /**
     * 检查 team 工具是否已注册
     */
    private boolean hasTeamToolsRegistered() {
        Toolkit toolkit = agent.getToolkit();
        if (toolkit == null) {
            return false;
        }
        // 检查 "team" 工具是否存在
        AgentTool teamTool = toolkit.getTool("team");
        return teamTool != null;
    }

    /**
     * 尝试恢复 team session
     */
    private void attemptRecovery() {
        // 防止并发恢复
        if (!recovering.compareAndSet(false, true)) {
            log.debug("Recovery already in progress, skipping");
            return;
        }

        // 冷却期检查
        long now = System.currentTimeMillis();
        if (now - lastRecoveryAttemptMs < RECOVERY_COOLDOWN_MS) {
            log.debug("Recovery cooldown, skipping");
            recovering.set(false);
            return;
        }

        lastRecoveryAttemptMs = now;

        try {
            log.info("Attempting to recover team session...");
            recoverTeamSession();
        } catch (Exception e) {
            log.error("Team session recovery failed", e);
        } finally {
            recovering.set(false);
        }
    }

    /**
     * 执行 team session 恢复
     *
     * <p>恢复策略（按优先级）：
     * <ol>
     *   <li>检查团队是否存在</li>
     *   <li>尝试通过控制面 API 触发 team_join</li>
     *   <li>如果自动恢复失败，记录手动干预指引</li>
     * </ol>
     */
    private void recoverTeamSession() {
        String controlPlaneHttp =
                System.getenv().getOrDefault("AISTIO_CONTROL_PLANE_HTTP", "http://localhost:8081");
        // 使用与 OrderFulfillmentExample 相同的默认 token
        String internalToken =
                System.getenv()
                        .getOrDefault(
                                "BUILDER_INTERNAL_TOKEN",
                                "local-dev-internal-token-at-least-32chars");

        try {
            ControlPlaneHttpClient httpClient =
                    new ControlPlaneHttpClient(controlPlaneHttp, internalToken);

            // 步骤 1：检查团队是否存在
            var teamResp =
                    httpClient.send(
                            "GET", "/api/v1/teams/" + teamName + "?namespace=" + namespace, null);

            if (teamResp.status() != 200) {
                log.error(
                        "Team {}/{} not found (status={}). Cannot recover.",
                        namespace,
                        teamName,
                        teamResp.status());
                logManualRecoveryInstructions();
                return;
            }

            log.info("Team {}/{} exists, attempting to trigger team_join...", namespace, teamName);

            // 步骤 2：尝试通过重新添加成员触发 team_join
            // 注意：这可能会导致冲突，但如果成功会触发 team_join
            var memberResp =
                    httpClient.send(
                            "POST",
                            "/api/v1/teams/" + teamName + "/members?namespace=" + namespace,
                            Map.of(
                                    "name", "lead",
                                    "agentRef", "fulfillment-lead",
                                    "deployMode", "byo"));

            if (memberResp.status() == 202) {
                log.info("Team member re-add accepted, team_join should be triggered");
                consecutiveFailures = 0;
            } else if (memberResp.status() == 409) {
                log.info(
                        "Team member already exists (conflict), team_join may not be re-triggered");
                logManualRecoveryInstructions();
            } else {
                log.warn(
                        "Team member re-add returned status={}: {}",
                        memberResp.status(),
                        memberResp.body());
                logManualRecoveryInstructions();
            }

        } catch (Exception e) {
            log.error("Failed to communicate with control plane", e);
            logManualRecoveryInstructions();
        }
    }

    /**
     * 记录手动恢复指引
     */
    private void logManualRecoveryInstructions() {
        log.warn("========================================");
        log.warn("Manual recovery required for team session:");
        log.warn("  Team: {}/{}", namespace, teamName);
        log.warn("  Agent: fulfillment-lead");
        log.warn("========================================");
        log.warn("Option 1: Restart control plane (clears all sessions)");
        log.warn("Option 2: Re-create team via console:");
        log.warn("  DELETE /api/v1/teams/{}?namespace={}", teamName, namespace);
        log.warn("  POST   /api/v1/teams (with team config)");
        log.warn("Option 3: Wait for control plane to auto-recover (if supported)");
        log.warn("========================================");
    }
}
