package com.robert.agent;

import com.robert.agent.model.AgentResult;
import com.robert.agent.model.SubAgentRequest;

import java.util.concurrent.CompletableFuture;

public interface SubAgent {
    String agentId();
    String description();
    CompletableFuture<AgentResult> execute(SubAgentRequest req, AgentEventListener listener);
}
