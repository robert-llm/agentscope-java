package com.robert.agent.router.subagent;


import com.robert.agent.router.AgentChatContext;

public class SubAgentRequest {
    private String question;
    private AgentChatContext context;
    private boolean deepThink;
    private int module;

    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }

    public AgentChatContext getContext() { return context; }
    public void setContext(AgentChatContext context) { this.context = context; }

    public boolean isDeepThink() { return deepThink; }
    public void setDeepThink(boolean deepThink) { this.deepThink = deepThink; }

    public int getModule() { return module; }
    public void setModule(int module) { this.module = module; }
}
