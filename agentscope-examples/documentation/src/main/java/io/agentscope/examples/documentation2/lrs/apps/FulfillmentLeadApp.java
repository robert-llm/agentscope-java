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
 * Fulfillment Lead Agent application — coordinates the order-fulfillment team.
 *
 * <p>Independent application that registers as {@code fulfillment-lead} External Agent.
 * This agent acts as the Team Lead, coordinating worker agents (order-agent, inventory-agent,
 * logistics-agent, after-sales-agent) within its team.
 *
 * <p><b>Architecture:</b>
 * <pre>
 * Router Agent (router-agent)
 *     │ routes requests to teams
 *     ▼
 * Fulfillment Lead Agent (this app)  ← Team Lead
 *     │ coordinates via team tool
 *     ├── order-agent
 *     ├── inventory-agent
 *     ├── logistics-agent
 *     └── after-sales-agent
 * </pre>
 *
 * <p><b>Run:</b>
 * <pre>
 *   mvn exec:java -pl agentscope-examples/documentation \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.lrs.apps.FulfillmentLeadApp
 * </pre>
 */
public class FulfillmentLeadApp {

    private static final Logger log = LoggerFactory.getLogger(FulfillmentLeadApp.class);
    private static final String AGENT_KEY = "fulfillment-lead";
    private static final int CONTRACT_PORT = 18091;
    private static final String TEAM_NAME = "OrderFulfillmentTeam2";
    private static final String NAMESPACE = "default";

    public static void main(String[] args) throws Exception {
        log.info("Starting {} ...", AGENT_KEY);

        var dataStore = OrderFulfillmentExample.loadDataStore();
        var example = new OrderFulfillmentExample(dataStore);
        AgentScopeAdapter adapter = new AgentScopeAdapter();
        HarnessAgent agent = example.buildFulfillmentLeadAgent(adapter);

        SessionBridge bridge =
                OrderFulfillmentExample.registerWithService(
                        agent, adapter, AGENT_KEY, null, CONTRACT_PORT);

        log.info("{} is running. Open the Service console to interact.", AGENT_KEY);
        log.info("Press Ctrl+C to stop.");

        // TODO: 启动 Session 健康监控器（需要控制面支持 rejoin API 后才能启用）
        // SessionHealthMonitor monitor =
        //         new SessionHealthMonitor(bridge, TEAM_NAME, NAMESPACE, agent);
        // monitor.start();

        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    log.info("Shutting down {} ...", AGENT_KEY);
                                    // monitor.stop();
                                    bridge.close();
                                }));

        Thread.currentThread().join();
    }
}
