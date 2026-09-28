package com.robert.agent.router;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一事件模型：RouterAgent 对外输出的唯一事件单位。
 *
 * <p>既承载子 Agent 的原始事件（message / document / table / ...），
 * 也承载主 Agent 的编排事件（plan / workflow_started / message_end）。</p>
 */
public class RouterEvent {

    public enum Type {
        WORKFLOW_STARTED,
        PLAN,
        NODE_STARTED,
        MESSAGE,          // 流式正文分片
        THINK,            // 深度思考分片
        THINK_TIME,
        TABLE,
        ORG,
        DOCUMENT,
        NODE_FINISHED,
        WORKFLOW_FINISHED,
        MESSAGE_END,
        ERROR
    }

    /** 事件类型 */
    private final Type type;

    /** 来源标记：-1=主 Agent，0=EIP，1=learning */
    private final int module;

    /** 事件来源的子 Agent id（主 Agent 事件为 null） */
    private final String agentId;

    /** 事件序号（递增，用于 SSE 断线恢复）。包级可见，供 RouterAgent 转发时赋值 */
    long seq;

    /** 事件负载（结构化数据） */
    private final Map<String, Object> payload;

    private RouterEvent(Type type, int module, String agentId, Map<String, Object> payload) {
        this.type = type;
        this.module = module;
        this.agentId = agentId;
        this.payload = payload != null ? payload : new LinkedHashMap<>();
    }

    // ==================== 静态工厂 ====================

    public static RouterEvent workflowStarted(long seq) {
        return withSeq(new RouterEvent(Type.WORKFLOW_STARTED, -1, null, null), seq);
    }

    public static RouterEvent workflowFinished(long seq) {
        return withSeq(new RouterEvent(Type.WORKFLOW_FINISHED, -1, null, null), seq);
    }

    public static RouterEvent messageEnd(long seq) {
        return withSeq(new RouterEvent(Type.MESSAGE_END, -1, null, null), seq);
    }

    public static RouterEvent plan(List<Map<String, Object>> nodeList, int finished, long seq) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("nodeList", nodeList);
        p.put("finished", finished);
        return withSeq(new RouterEvent(Type.PLAN, -1, null, p), seq);
    }

    public static RouterEvent message(String fragment, int module, String agentId, long seq) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("answer", fragment);
        return withSeq(new RouterEvent(Type.MESSAGE, module, agentId, p), seq);
    }

    public static RouterEvent think(String fragment, int module, String agentId, long seq) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("answer", fragment);
        return withSeq(new RouterEvent(Type.THINK, module, agentId, p), seq);
    }

    public static RouterEvent thinkTime(String thinkTime, int module, String agentId, long seq) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("think_time", thinkTime);
        return withSeq(new RouterEvent(Type.THINK_TIME, module, agentId, p), seq);
    }

    public static RouterEvent nodeStarted(String nodeType, int module, String agentId, long seq) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("node_type", nodeType);
        return withSeq(new RouterEvent(Type.NODE_STARTED, module, agentId, p), seq);
    }

    public static RouterEvent nodeFinished(String nodeType, int module, String agentId, long seq) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("node_type", nodeType);
        return withSeq(new RouterEvent(Type.NODE_FINISHED, module, agentId, p), seq);
    }

    public static RouterEvent table(Object tableMap, int module, String agentId, long seq) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("tableMap", tableMap);
        return withSeq(new RouterEvent(Type.TABLE, module, agentId, p), seq);
    }

    public static RouterEvent org(Object orgMap, int module, String agentId, long seq) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("orgMap", orgMap);
        return withSeq(new RouterEvent(Type.ORG, module, agentId, p), seq);
    }

    public static RouterEvent document(Object documentPayload, int module, String agentId, long seq) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("documentPayload", documentPayload);
        return withSeq(new RouterEvent(Type.DOCUMENT, module, agentId, p), seq);
    }

    public static RouterEvent error(String message, long seq) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("message", message);
        return withSeq(new RouterEvent(Type.ERROR, -1, null, p), seq);
    }

    private static RouterEvent withSeq(RouterEvent event, long seq) {
        event.seq = seq;
        return event;
    }

    // ==================== 便捷判断 ====================

    public boolean isTerminal() {
        return type == Type.MESSAGE_END;
    }

    public boolean isMessage() {
        return type == Type.MESSAGE || type == Type.THINK;
    }

    // ==================== Getter ====================

    public Type getType() { return type; }
    public int getModule() { return module; }
    public String getAgentId() { return agentId; }
    public long getSeq() { return seq; }
    public Map<String, Object> getPayload() { return payload; }
}
