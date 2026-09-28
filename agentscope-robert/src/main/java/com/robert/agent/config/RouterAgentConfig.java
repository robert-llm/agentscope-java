package com.robert.agent.config;

import com.robert.agent.AgentRegistry;
//import com.robert.agent.EIPSubAgent;
import com.robert.agent.RouterAgent;
import com.robert.agent.*;
import io.agentscope.core.ReActAgent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class RouterAgentConfig {

    @Bean("agentExecutor")
    public ExecutorService agentExecutor() {
        return Executors.newFixedThreadPool(
                Runtime.getRuntime().availableProcessors() * 2
        );
    }

    @Bean("routerReActAgent")
    public ReActAgent routerReActAgent() {
        return ReActAgent.builder()
                .name("master-router")
                .model("dashscope:qwen-plus")
                .build();
    }

//    @Bean
//    public AgentRegistry agentRegistry(ReActSmartAgent reActSmartAgent,
//                                       ExecutorService agentExecutor) {
//        AgentRegistry registry = new AgentRegistry();
//        registry.register(new EIPSubAgent(reActSmartAgent, agentExecutor));
//        // registry.register(new LearningSubAgent(...));
//        return registry;
//    }

    @Bean
    public RouterAgent routerAgent(ReActAgent routerReActAgent,
                                   AgentRegistry agentRegistry,
                                   ExecutorService agentExecutor) {
        return new RouterAgent(routerReActAgent, agentRegistry, agentExecutor, 90);
    }
}
