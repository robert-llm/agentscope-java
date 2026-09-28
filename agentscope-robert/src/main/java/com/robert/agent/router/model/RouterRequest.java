package com.robert.agent.router.model;

import com.robert.agent.router.AgentChatContext;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * RouterAgent 的输入请求。
 *
 * <p>可从 {@link AgentChatContext} 转换，也可由调用方直接构造（流式场景）。</p>
 */
public class RouterRequest {

    private String question;
    private String conversationId;
    private String messageId;
    private String taskId;
    private String loginName;
    private String adGuid;
    private String orgId;
    private boolean deepThink;

    /** 可选：强制指定路由目标（跳过 LLM 规划），用于测试或参数显式指定 */
    private List<String> forcedTargets;

    /** 取消标记（由调用方或 Flux 取消回调触发） */
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public static RouterRequest from(AgentChatContext ctx) {
        RouterRequest r = new RouterRequest();
        r.question = ctx.getQuestion();
        r.conversationId = ctx.getConversationId();
        r.messageId = ctx.getMessageId();
        r.taskId = ctx.getTaskId();
        r.loginName = ctx.getLoginName();
        r.adGuid = ctx.getAdGuid();
        r.orgId = ctx.getOrgId();
        r.deepThink = ctx.getDeepThink();
        return r;
    }

    public void markCancelled() { cancelled.set(true); }
    public boolean isCancelled() { return cancelled.get(); }

    // ==================== Getter / Setter ====================

    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }

    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }

    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }

    public String getLoginName() { return loginName; }
    public void setLoginName(String loginName) { this.loginName = loginName; }

    public String getAdGuid() { return adGuid; }
    public void setAdGuid(String adGuid) { this.adGuid = adGuid; }

    public String getOrgId() { return orgId; }
    public void setOrgId(String orgId) { this.orgId = orgId; }

    public boolean getDeepThink() { return deepThink; }
    public void setDeepThink(boolean deepThink) { this.deepThink = deepThink; }

    public List<String> getForcedTargets() { return forcedTargets; }
    public void setForcedTargets(List<String> forcedTargets) { this.forcedTargets = forcedTargets; }
}
