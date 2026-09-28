package com.robert.agent;

import java.util.*;

public class AgentRegistry {
    private final Map<String, SubAgent> agents = new LinkedHashMap<>();

    public void register(SubAgent agent) {
        agents.put(agent.agentId(), agent);
    }

    public SubAgent get(String agentId) {
        return agents.get(agentId);
    }

    public Collection<SubAgent> agents() {
        return Collections.unmodifiableCollection(agents.values());
    }
}
