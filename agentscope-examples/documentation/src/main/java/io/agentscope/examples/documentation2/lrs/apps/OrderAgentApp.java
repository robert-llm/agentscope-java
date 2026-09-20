/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.documentation2.lrs.apps;

import io.agentscope.examples.documentation2.lrs.OrderFulfillmentExample;
import io.agentscope.extensions.aistio.SessionBridge;
import io.agentscope.extensions.aistio.adapter.AgentScopeAdapter;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Order Agent application — queries order status, version, and source channel.
 *
 * <p>This is an independent application that registers with the AgentScope Service as an External
 * Agent. In the Service console, it appears as {@code order-agent} and can be added as a Team
 * member.
 *
 * <p><b>Run:</b>
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.lrs.apps.OrderAgentApp
 * </pre>
 *
 * <p><b>Environment variables:</b>
 * <ul>
 *   <li>{@code DASHSCOPE_API_KEY} (required) — DashScope API key</li>
 *   <li>{@code AISTIO_CONTROL_PLANE_HTTP} (default: http://localhost:8081) — Service URL</li>
 *   <li>{@code BUILDER_INTERNAL_TOKEN} — Service internal token</li>
 *   <li>{@code AISTIO_CONTRACT_PORT} (default: 18090) — Contract HTTP server port</li>
 * </ul>
 */
public class OrderAgentApp {

    private static final Logger log = LoggerFactory.getLogger(OrderAgentApp.class);
    private static final String AGENT_KEY = "order-agent";
    private static final int CONTRACT_PORT = 18092;

    public static void main(String[] args) throws Exception {
        log.info("Starting {} ...", AGENT_KEY);

        // 1. Load shared business data
        var dataStore = OrderFulfillmentExample.loadDataStore();
        var example = new OrderFulfillmentExample(dataStore);

        // 2. Create adapter (provides observation middleware)
        AgentScopeAdapter adapter = new AgentScopeAdapter();

        // 3. Build the agent with tools and middleware
        HarnessAgent agent = example.buildOrderAgent(adapter);

        // 4. Register with Service (no TeamClient — this is a member, not the leader)
        SessionBridge bridge =
                OrderFulfillmentExample.registerWithService(
                        agent, adapter, AGENT_KEY, null, CONTRACT_PORT);

        log.info("{} is running. Open the Service console to interact.", AGENT_KEY);
        log.info("Press Ctrl+C to stop.");

        // 5. Keep alive until shutdown
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    log.info("Shutting down {} ...", AGENT_KEY);
                                    bridge.close();
                                }));

        Thread.currentThread().join();
    }
}
