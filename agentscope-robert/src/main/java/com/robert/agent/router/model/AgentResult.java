package com.robert.agent.router.model;

import java.util.Map;

public class AgentResult {
    private String agentId;
    private String answer;
    private String thinkContent;
    private int status; // 0=成功 1=失败 2=超时
    private Map<String, Object> chunkMap;
    private Map<String, Object> documentMap;

    public static AgentResult degraded(String agentId, Throwable t) {
        AgentResult r = new AgentResult();
        r.agentId = agentId;
        r.status = (t instanceof java.util.concurrent.TimeoutException) ? 2 : 1;
        r.answer = "该部分内容获取失败，请稍后重试。";
        return r;
    }

    // getters / setters ...
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }

    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }

    public String getThinkContent() { return thinkContent; }
    public void setThinkContent(String thinkContent) { this.thinkContent = thinkContent; }

    public int getStatus() { return status; }
    public void setStatus(int status) { this.status = status; }

    public Map<String, Object> getChunkMap() { return chunkMap; }
    public void setChunkMap(Map<String, Object> chunkMap) { this.chunkMap = chunkMap; }

    public Map<String, Object> getDocumentMap() { return documentMap; }
    public void setDocumentMap(Map<String, Object> documentMap) { this.documentMap = documentMap; }
}
