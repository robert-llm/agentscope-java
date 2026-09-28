package com.robert.agent.router;

import com.robert.agent.router.model.AgentResult;

public interface AgentEventListener {
    void onNodeStarted(String nodeType, int module);
    void onPlan(String nodeName, int status, String nodeKey, boolean finished);
    void onMessage(String answerFragment, int module);
    void onTable(Object tablePayload, int module);
    void onOrg(Object orgPayload, int module);
    void onDocument(Object docPayload, int module);
    void onThinkTime(String thinkTime, int module);
    void onNodeFinished(String nodeType, int module);
    void onFinished(String agentId, AgentResult result);
    void onError(String agentId, Throwable t);
}
