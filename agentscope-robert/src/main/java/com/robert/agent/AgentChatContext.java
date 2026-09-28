package com.robert.agent;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 对话上下文：承载一轮对话的全部状态。
 *
 * <p>改造后新增三块能力：
 * <ul>
 *   <li>{@link #eventSink} / {@link #module}：供 RouterAgent 编排层注入事件出口与来源标记</li>
 *   <li>{@link #sendPlan} / {@link #sendMessage} / {@link #sendEvent}：事件统一出口，
 *       单 Agent 直通走 SseEmitter，多 Agent 走 EventSink 转发</li>
 *   <li>{@link #streamAnswer} / {@link #streamThink} / {@link #chunkMap} / {@link #documentMap}：
 *       聚合器回填用，供 DB 持久化</li>
 * </ul></p>
 */
public class AgentChatContext {

    // ==================== 存量字段（原有） ====================

    /** 会话 ID */
    private String conversationId;

    /** 消息 ID（一轮对话唯一） */
    private String messageId;

    /** 任务 ID（用于埋点/追踪） */
    private String taskId;

    /** 用户问题 */
    private String question;

    /** 用户登录名 */
    private String loginName;

    /** 用户 AD GUID */
    private String adGuid;

    /** 用户所属组织 ID */
    private String orgId;

    /** 是否深度思考（主 Agent 不消费，原样透传给子 Agent） */
    private boolean deepThink;

    /** SSE 发射器（单 Agent 直通时使用） */
    private SseEmitter sseEmitter;

    /** 写锁（存量 SSE 多线程写保护） */
    private final ReentrantLock lock = new ReentrantLock();

    /** 历史对话记忆（注入子 Agent 的 ReAct 上下文） */
    private List<Map<String, Object>> chatHistory = new ArrayList<>();

    /** 语义缓存 key / 命中标记等 */
    private String cacheKey;
    private boolean cacheHit;

    /** 用户停止标记（与 Redis 队列联动） */
    private volatile boolean waitStop;

    // ==================== 新增字段（RouterAgent 编排层） ====================

    /**
     * 事件出口。
     * <ul>
     *   <li>单 Agent 直通：{@code null}，走 {@link #sseEmitter} 原路径</li>
     *   <li>多 Agent 编排：由 {@code EIPSubAgent} / {@code LearningSubAgent} 注入，
     *       事件追加 module 后立即转发（不缓冲、不重排）</li>
     * </ul>
     */
    private EventSink eventSink;

    /**
     * 当前子 Agent 的来源标记。
     * <ul>
     *   <li>-1：单 Agent 直通（剥除 module）</li>
     *   <li>0：EIP 子 Agent</li>
     *   <li>1：学习平台子 Agent</li>
     * </ul>
     */
    private int module = -1;

    // ==================== 流式回答缓存（聚合器回填 / DB 持久化用） ====================

    /** 流式回答缓冲（正文，不含 think） */
    private final StringBuilder streamAnswer = new StringBuilder();

    /** 流式思考缓冲（think 内容，deepThink=YES 时） */
    private final StringBuilder streamThink = new StringBuilder();

    /** 引用文档 chunk 池（key = 引用编号） */
    private final Map<String, Object> chunkMap = new LinkedHashMap<>();

    /** 引用文档列表池（key = documentId） */
    private final Map<String, Object> documentMap = new LinkedHashMap<>();

    // ==================== 构造 ====================

    public AgentChatContext() {
    }

    public AgentChatContext(String conversationId, String messageId, String question,
                            String loginName, String adGuid, String orgId, boolean deepThink) {
        this.conversationId = conversationId;
        this.messageId = messageId;
        this.question = question;
        this.loginName = loginName;
        this.adGuid = adGuid;
        this.orgId = orgId;
        this.deepThink = deepThink;
    }

    // ==================== 事件发送统一出口 ====================

    /**
     * 发送 plan 事件（子 Agent 级节点进度）。
     *
     * @param nodeList  节点列表 [{nodeName,nodeStatus,executionCount,nodeKey}, ...]
     * @param finished  0=未完成 1=已完成
     */
    public void sendPlan(List<Map<String, Object>> nodeList, int finished) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("nodeList", nodeList);
        data.put("finished", finished);
        sendRawEvent("plan", data);
    }

    /**
     * 发送 message 事件（流式回答分片）。
     */
    public void sendMessage(String answerFragment, int module) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (module >= 0) {
            data.put("module", module);
        }
        sendRawEvent("message", data, answerFragment);
    }

    /**
     * 发送扩展事件（node_started / node_finished / table / org / document / think_time 等）。
     */
    public void sendEvent(String eventName, Map<String, Object> data) {
        sendRawEvent(eventName, data);
    }

    /**
     * 底层事件出口：多 Agent 走 EventSink 转发；单 Agent 直通走 SseEmitter。
     */
    private void sendRawEvent(String eventName, Map<String, Object> data) {
        sendRawEvent(eventName, data, null);
    }

    private void sendRawEvent(String eventName, Map<String, Object> data, String answer) {
        // 构造事件 JSON（由 EventObjectConvert / 序列化工具完成，此处简化）
        String eventJson = EventObjectSerializer.serialize(
                eventName, conversationId, messageId, taskId, answer, data
        );

        if (eventSink != null) {
            eventSink.send(eventJson, module >= 0 ? module : -1);
            return;
        }

        // 单 Agent 直通：与改造前逐字节一致
        if (sseEmitter == null) {
            return;
        }
        lock.lock();
        try {
            sseEmitter.send(SseEmitter.event().data(eventJson));
        } catch (IOException e) {
            throw new RuntimeException("SSE 发送失败", e);
        } finally {
            lock.unlock();
        }
    }

    // ==================== Getter / Setter ====================

    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }

    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }

    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }

    public String getLoginName() { return loginName; }
    public void setLoginName(String loginName) { this.loginName = loginName; }

    public String getAdGuid() { return adGuid; }
    public void setAdGuid(String adGuid) { this.adGuid = adGuid; }

    public String getOrgId() { return orgId; }
    public void setOrgId(String orgId) { this.orgId = orgId; }

    public boolean getDeepThink() { return deepThink; }
    public void setDeepThink(boolean deepThink) { this.deepThink = deepThink; }

    public SseEmitter getSseEmitter() { return sseEmitter; }
    public void setSseEmitter(SseEmitter sseEmitter) { this.sseEmitter = sseEmitter; }

    public ReentrantLock getLock() { return lock; }

    public List<Map<String, Object>> getChatHistory() { return chatHistory; }
    public void setChatHistory(List<Map<String, Object>> chatHistory) { this.chatHistory = chatHistory; }

    public String getCacheKey() { return cacheKey; }
    public void setCacheKey(String cacheKey) { this.cacheKey = cacheKey; }

    public boolean isCacheHit() { return cacheHit; }
    public void setCacheHit(boolean cacheHit) { this.cacheHit = cacheHit; }

    public boolean isWaitStop() { return waitStop; }
    public void setWaitStop(boolean waitStop) { this.waitStop = waitStop; }

    public EventSink getEventSink() { return eventSink; }
    public void setEventSink(EventSink eventSink) { this.eventSink = eventSink; }

    public int getModule() { return module; }
    public void setModule(int module) { this.module = module; }

    public StringBuilder getStreamAnswer() { return streamAnswer; }
    public StringBuilder getStreamThink() { return streamThink; }

    public Map<String, Object> getChunkMap() { return chunkMap; }
    public Map<String, Object> getDocumentMap() { return documentMap; }
}
