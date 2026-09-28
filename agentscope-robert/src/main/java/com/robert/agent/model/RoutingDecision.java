package com.robert.agent.model;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;

public class RoutingDecision {
    @JsonPropertyDescription("简洁单行思考摘要，禁止英文双引号、反斜杠、控制字符")
    public String thought;

    @JsonPropertyDescription("子 Agent id 列表，必须与可用列表中的 id 完全一致，同一 Agent 最多出现一次")
    public List<String> subAgents;

    public String getThought() { return thought; }
    public void setThought(String thought) { this.thought = thought; }

    public List<String> getSubAgents() { return subAgents; }
    public void setSubAgents(List<String> subAgents) { this.subAgents = subAgents; }
}
