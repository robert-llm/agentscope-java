package com.robert.agent;

import com.robert.agent.model.AgentResult;

import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

public class StreamAggregator {

    private static final Logger log = Logger.getLogger(StreamAggregator.class.getName());

    private final AgentChatContext ctx;
    private final List<String> targets;
    private final AgentRegistry registry;
    private final ExecutorService agentExecutor;
    private final int agentTimeoutSec;

    private final Map<String, AgentResult> segmentResults = new LinkedHashMap<>();
    private int citationOffset = 0;

    public StreamAggregator(AgentChatContext ctx, List<String> targets,
                            AgentRegistry registry, ExecutorService agentExecutor,
                            int agentTimeoutSec) {
        this.ctx = ctx;
        this.targets = targets;
        this.registry = registry;
        this.agentExecutor = agentExecutor;
        this.agentTimeoutSec = agentTimeoutSec;
    }

    public void emitInitialPlan() {
        List<Map<String, Object>> nodeList = new ArrayList<>();
        for (String id : targets) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("nodeName", nodeName(id));
            node.put("nodeStatus", 0);
            node.put("executionCount", 1);
            node.put("nodeKey", nodeKey(id));
            nodeList.add(node);
        }
        ctx.sendPlan(nodeList, 0);
    }

    public void emitPlanUpdate(String agentId, AgentResult result) {
        segmentResults.put(agentId, result);

        List<Map<String, Object>> nodeList = new ArrayList<>();
        for (String id : targets) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("nodeName", nodeName(id));
            node.put("nodeStatus", segmentResults.containsKey(id) ? 1 : 0);
            node.put("executionCount", 1);
            node.put("nodeKey", nodeKey(id));
            nodeList.add(node);
        }
        int finished = segmentResults.size() >= targets.size() ? 1 : 0;
        ctx.sendPlan(nodeList, finished);
    }

    public AgentEventListener listener(int module) {
        return new AgentEventListener() {
            @Override
            public void onNodeStarted(String nodeType, int m) {
                ctx.sendEvent("node_started", Map.of("node_type", nodeType, "module", module));
            }

            @Override
            public void onPlan(String nodeName, int status, String nodeKey, boolean finished) {
                // 子 Agent 内部 plan 由聚合器统一管理，此处忽略
            }

            @Override
            public void onMessage(String answerFragment, int m) {
                ctx.sendMessage(answerFragment, module);
            }

            @Override
            public void onTable(Object tablePayload, int m) {
                ctx.sendEvent("table", Map.of("tableMap", tablePayload, "module", module));
            }

            @Override
            public void onOrg(Object orgPayload, int m) {
                ctx.sendEvent("org", Map.of("orgMap", orgPayload, "module", module));
            }

            @Override
            public void onDocument(Object docPayload, int m) {
                Object adjusted = adjustCitations(docPayload, module);
                ctx.sendEvent("document", Map.of("documentPayload", adjusted, "module", module));
            }

            @Override
            public void onThinkTime(String thinkTime, int m) {
                ctx.sendEvent("think_time", Map.of("think_time", thinkTime, "module", module));
            }

            @Override
            public void onNodeFinished(String nodeType, int m) {
                ctx.sendEvent("node_finished", Map.of("node_type", nodeType, "module", module));
            }

            @Override
            public void onFinished(String agentId, AgentResult result) {
                log.info("[aggregator] agent=" + agentId + " finished");
            }

            @Override
            public void onError(String agentId, Throwable t) {
                log.warning("[aggregator] agent=" + agentId + " error: " + t.getMessage());
            }
        };
    }

    public void emitDegraded(String agentId, String message) {
        int module = moduleOf(agentId);
        ctx.sendEvent("node_started", Map.of("node_type", "llm", "module", module));
        ctx.sendMessage(message, module);
        ctx.sendEvent("node_finished", Map.of("node_type", "llm", "module", module));
        segmentResults.put(agentId, AgentResult.degraded(agentId, null));
    }

    private Object adjustCitations(Object docPayload, int module) {
        if (module == 0) {
            citationOffset += countCitations(docPayload);
            return docPayload;
        }
        Object adjusted = rewriteCitations(docPayload, citationOffset);
        citationOffset += countCitations(docPayload);
        return adjusted;
    }

    private int countCitations(Object payload) {
        // TODO: 实际实现从 chunkMap / documentList 统计
        return 0;
    }

    private Object rewriteCitations(Object payload, int offset) {
        // TODO: 实际实现遍历改写引用编号
        return payload;
    }

    public void flushToContext() {
        StringBuilder mergedAnswer = new StringBuilder();
        StringBuilder mergedThink = new StringBuilder();
        Map<String, Object> mergedChunkMap = new LinkedHashMap<>();
        Map<String, Object> mergedDocMap = new LinkedHashMap<>();

        for (String id : targets) {
            AgentResult r = segmentResults.get(id);
            if (r == null) continue;
            if (r.getAnswer() != null) mergedAnswer.append(r.getAnswer());
            if (r.getThinkContent() != null) mergedThink.append(r.getThinkContent());
            if (r.getChunkMap() != null) mergedChunkMap.putAll(r.getChunkMap());
            if (r.getDocumentMap() != null) mergedDocMap.putAll(r.getDocumentMap());
        }

        ctx.getStreamAnswer().append(mergedAnswer.toString());
        if (mergedThink.length() > 0) {
            ctx.getStreamThink().append(mergedThink.toString());
        }
        ctx.getChunkMap().putAll(mergedChunkMap);
        ctx.getDocumentMap().putAll(mergedDocMap);
    }

    private int moduleOf(String agentId) {
        return targets.indexOf(agentId);
    }

    private String nodeName(String agentId) {
        return "eip-agent".equals(agentId) ? "EIP 制度检索" : "学习平台课程检索";
    }

    private String nodeKey(String agentId) {
        return "eip-agent".equals(agentId) ? "book" : "course";
    }
}
