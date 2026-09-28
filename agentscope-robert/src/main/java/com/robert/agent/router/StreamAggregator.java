package com.robert.agent.router;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * 流式聚合器：管理 plan 状态、节点进度、引用偏移。
 *
 * <p>子 Agent 的流式事件由 RouterAgent 直接转发到 sink，
 * 聚合器只负责 plan 节点的进度追踪和初始/更新 plan 事件的发射。</p>
 */
public class StreamAggregator {

    private static final Logger log = Logger.getLogger(StreamAggregator.class.getName());

    private final Consumer<RouterEvent> eventConsumer;
    private final AtomicLong seqGenerator;
    private final List<String> targets;
    private final AgentRegistry registry;

    /** 已完成的子 Agent 集合（用于 plan 节点状态追踪） */
    private final Set<String> completedAgents = new LinkedHashSet<>();
    private int citationOffset = 0;

    public StreamAggregator(Consumer<RouterEvent> eventConsumer,
                            AtomicLong seqGenerator,
                            List<String> targets,
                            AgentRegistry registry) {
        this.eventConsumer = eventConsumer;
        this.seqGenerator = seqGenerator;
        this.targets = targets;
        this.registry = registry;
    }

    // ==================== 初始 plan / 更新 plan ====================

    public void emitInitialPlan() {
        eventConsumer.accept(RouterEvent.plan(buildNodeList(), 0, nextSeq()));
    }

    /**
     * 子 Agent 完成后调用，更新 plan 节点状态。
     */
    public void emitPlanUpdate(String agentId) {
        completedAgents.add(agentId);
        int finished = completedAgents.size() >= targets.size() ? 1 : 0;
        eventConsumer.accept(RouterEvent.plan(buildNodeList(), finished, nextSeq()));
    }

    private List<Map<String, Object>> buildNodeList() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (String id : targets) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("nodeName", nodeName(id));
            node.put("nodeStatus", completedAgents.contains(id) ? 1 : 0);
            node.put("executionCount", 1);
            node.put("nodeKey", nodeKey(id));
            list.add(node);
        }
        return list;
    }

    // ==================== 降级处理 ====================

    /**
     * 子 Agent 未注册或不可用：以降级提示补位，保持事件结构同构。
     */
    public void emitDegraded(String agentId, String message) {
        int module = moduleOf(agentId);
        eventConsumer.accept(RouterEvent.nodeStarted("llm", module, agentId, nextSeq()));
        eventConsumer.accept(RouterEvent.message(message, module, agentId, nextSeq()));
        eventConsumer.accept(RouterEvent.nodeFinished("llm", module, agentId, nextSeq()));
        completedAgents.add(agentId);
    }

    // ==================== 引用 offset 修正（占位） ====================

    private Object adjustCitations(Object docPayload, int module) {
        if (module == 0) {
            citationOffset += countCitations(docPayload);
            return docPayload;
        }
        Object adjusted = rewriteCitations(docPayload, citationOffset);
        citationOffset += countCitations(docPayload);
        return adjusted;
    }

    private int countCitations(Object payload) { return 0; }
    private Object rewriteCitations(Object payload, int offset) { return payload; }

    // ==================== 辅助 ====================

    private long nextSeq() { return seqGenerator.incrementAndGet(); }

    private int moduleOf(String agentId) { return targets.indexOf(agentId); }

    private String nodeName(String agentId) {
        return "eip-agent".equals(agentId) ? "EIP 制度检索" : "学习平台课程检索";
    }

    private String nodeKey(String agentId) {
        return "eip-agent".equals(agentId) ? "book" : "course";
    }

    public Set<String> getCompletedAgents() { return completedAgents; }
}
