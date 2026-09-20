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
 * After-Sales Agent application — queries policies, creates and queries resolution tickets.
 *
 * <p>Independent application that registers as {@code after-sales-agent} External Agent.
 *
 * <p><b>Run:</b>
 * <pre>
 *   mvn exec:java -pl agentscope-examples/documentation \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.lrs.apps.AfterSalesAgentApp
 * </pre>
 */
public class AfterSalesAgentApp {

    private static final Logger log = LoggerFactory.getLogger(AfterSalesAgentApp.class);
    private static final String AGENT_KEY = "after-sales-agent";
    private static final int CONTRACT_PORT = 18095;

    public static void main(String[] args) throws Exception {
        log.info("Starting {} ...", AGENT_KEY);

        var dataStore = OrderFulfillmentExample.loadDataStore();
        var example = new OrderFulfillmentExample(dataStore);
        AgentScopeAdapter adapter = new AgentScopeAdapter();
        HarnessAgent agent = example.buildAfterSalesAgent(adapter);

        SessionBridge bridge =
                OrderFulfillmentExample.registerWithService(
                        agent, adapter, AGENT_KEY, null, CONTRACT_PORT);

        log.info("{} is running. Open the Service console to interact.", AGENT_KEY);
        log.info("Press Ctrl+C to stop.");

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
