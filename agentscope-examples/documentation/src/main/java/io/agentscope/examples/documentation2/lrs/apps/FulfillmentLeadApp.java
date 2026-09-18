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
import io.agentscope.extensions.aistio.transport.ControlPlaneHttpClient;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.team.TeamClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fulfillment Lead Agent application — the Team coordinator.
 *
 * <p>This is the Leader of the order-fulfillment Team. It registers with the AgentScope Service as
 * {@code fulfillment-lead} and supports Team coordination via the control-plane TeamClient.
 *
 * <p>When the Service sends a {@code team_join} command, the adapter creates a TeamsMiddleware
 * that injects team tools (list/claim tasks, send messages) into the agent's toolkit, enabling the
 * leader to delegate investigation to order, inventory, logistics and after-sales members.
 *
 * <p><b>Run:</b>
 *
 * <pre>
 *   mvn exec:java -pl agentscope-examples/documentation \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.lrs.apps.FulfillmentLeadApp
 * </pre>
 *
 * <p><b>Environment variables:</b>
 *
 * <ul>
 *   <li>{@code DASHSCOPE_API_KEY} (required) — DashScope API key
 *   <li>{@code AISTIO_CONTROL_PLANE_HTTP} (default: http://localhost:8081) — Service URL
 *   <li>{@code BUILDER_INTERNAL_TOKEN} — Service internal token
 *   <li>{@code AISTIO_CONTRACT_PORT} (default: 18090) — Contract HTTP server port
 * </ul>
 */
public class FulfillmentLeadApp {

    private static final Logger log = LoggerFactory.getLogger(FulfillmentLeadApp.class);
    private static final String AGENT_KEY = "fulfillment-lead";
    private static final int CONTRACT_PORT = 18091;

    public static void main(String[] args) throws Exception {
        log.info("Starting {} ...", AGENT_KEY);

        // 1. Load shared business data
        var dataStore = OrderFulfillmentExample.loadDataStore();
        var example = new OrderFulfillmentExample(dataStore);

        // 2. Create adapter (provides observation middleware)
        AgentScopeAdapter adapter = new AgentScopeAdapter();

        // 3. Build the agent with middleware
        HarnessAgent agent = example.buildFulfillmentLeadAgent(adapter);

        // 4. Create TeamClient for team coordination via control plane.
        //    registerWithService() will wire HarnessTeamSessionStarter on the adapter
        //    so that team_join / team_leave commands are handled automatically.
        String controlPlaneHttp =
                OrderFulfillmentExample.envOr("AISTIO_CONTROL_PLANE_HTTP", "http://localhost:8081");
        String internalToken =
                OrderFulfillmentExample.envOr(
                        "BUILDER_INTERNAL_TOKEN", OrderFulfillmentExample.DEFAULT_INTERNAL_TOKEN);
        ControlPlaneHttpClient httpClient =
                new ControlPlaneHttpClient(controlPlaneHttp, internalToken);
        TeamClient teamClient =
                new io.agentscope.extensions.aistio.store.ControlPlaneTeamClient(httpClient);

        // 5. Register with Service (TeamClient enables team coordination on the leader)
        SessionBridge bridge =
                OrderFulfillmentExample.registerWithService(agent, adapter, AGENT_KEY, teamClient);

        log.info("{} is running. Open the Service console to interact.", AGENT_KEY);
        log.info("In DESIGN → Teams, select this agent as Leader and add members.");
        log.info("Press Ctrl+C to stop.");

        // 6. Keep alive until shutdown
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
