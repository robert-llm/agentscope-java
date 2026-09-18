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
package io.agentscope.examples.documentation2.lrs;

import io.agentscope.core.tool.Toolkit;
import io.agentscope.examples.documentation2.lrs.business.BusinessDataStore;
import io.agentscope.examples.documentation2.lrs.tools.AfterSalesTools;
import io.agentscope.examples.documentation2.lrs.tools.InventoryTools;
import io.agentscope.examples.documentation2.lrs.tools.LogisticsTools;
import io.agentscope.examples.documentation2.lrs.tools.OrderTools;
import io.agentscope.extensions.aistio.Aistio;
import io.agentscope.extensions.aistio.AistioConfig;
import io.agentscope.extensions.aistio.SessionBridge;
import io.agentscope.extensions.aistio.adapter.AgentScopeAdapter;
import io.agentscope.extensions.aistio.adapter.HarnessTeamSessionStarter;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.team.TeamClient;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared bootstrap logic for the five order-fulfillment External Agents.
 *
 * <p>Each of the five agents runs as an independent application. This class provides the common
 * infrastructure: loading business data, building agents with the correct tools and prompts, and
 * registering with the AgentScope Service via {@link Aistio#instrument}.
 *
 * <h2>Architecture</h2>
 *
 * <pre>{@code
 * ┌─────────────────────┐   ┌──────────────────────┐
 * │  fulfillment-lead   │   │   AgentScope Service │
 * │  (Leader / coord.)  │◄─►│   Control Plane      │
 * └────────┬────────────┘   └──────┬───────────────┘
 *          │ Team                  │
 * ┌────────┼────────────────┐      │
 * │  order-agent            │      │
 * │  inventory-agent        │      │
 * │  logistics-agent        │      │
 * │  after-sales-agent      │      │
 * └─────────────────────────┘      │
 *         ▲                        │
 *         └────────────────────────┘
 *       Aistio.instrument() registration
 * }</pre>
 *
 * <h2>Agents</h2>
 * <ul>
 *   <li><b>fulfillment-lead</b> — coordinates investigation; produces unified conclusions.</li>
 *   <li><b>order-agent</b> — queries order status, version, source channel.</li>
 *   <li><b>inventory-agent</b> — queries warehouse stock levels. Does NOT reserve inventory.</li>
 *   <li><b>logistics-agent</b> — queries shipping status, estimated arrival.</li>
 *   <li><b>after-sales-agent</b> — queries policies, creates/queries resolution tickets.</li>
 * </ul>
 */
public final class OrderFulfillmentExample {

    private static final Logger log = LoggerFactory.getLogger(OrderFulfillmentExample.class);

    /** Default internal token matching the source docker-compose default. */
    public static final String DEFAULT_INTERNAL_TOKEN =
            "local-dev-internal-token-at-least-32chars";

    private final BusinessDataStore dataStore;

    public OrderFulfillmentExample(BusinessDataStore dataStore) {
        this.dataStore = dataStore;
    }

    // ---- Agent builders (return HarnessAgent for Service registration) --------

    public HarnessAgent buildFulfillmentLeadAgent(AgentScopeAdapter adapter) {
        return HarnessAgent.builder()
                .name("fulfillment-lead")
                .description(
                        "Coordinates order-fulfillment investigation across order, inventory,"
                                + " logistics and after-sales agents.")
                .sysPrompt(fulfillmentLeadPrompt())
                .model("dashscope:qwen-plus")
                .toolkit(new Toolkit())
                .workspace(agentWorkspace("fulfillment-lead"))
                .middleware(adapter.middleware())
                .maxIters(20)
                .build();
    }

    public HarnessAgent buildOrderAgent(AgentScopeAdapter adapter) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new OrderTools(dataStore));

        return HarnessAgent.builder()
                .name("order-agent")
                .description(
                        "Queries order status, version, and source channel."
                                + " Executes allowed order changes after version validation.")
                .sysPrompt(orderAgentPrompt())
                .model("dashscope:qwen-plus")
                .toolkit(toolkit)
                .workspace(agentWorkspace("order-agent"))
                .middleware(adapter.middleware())
                .maxIters(10)
                .build();
    }

    public HarnessAgent buildInventoryAgent(AgentScopeAdapter adapter) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new InventoryTools(dataStore));

        return HarnessAgent.builder()
                .name("inventory-agent")
                .description(
                        "Queries available stock across warehouses, capacity constraints,"
                                + " and transfer conditions. Does NOT reserve inventory.")
                .sysPrompt(inventoryAgentPrompt())
                .model("dashscope:qwen-plus")
                .toolkit(toolkit)
                .workspace(agentWorkspace("inventory-agent"))
                .middleware(adapter.middleware())
                .maxIters(10)
                .build();
    }

    public HarnessAgent buildLogisticsAgent(AgentScopeAdapter adapter) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new LogisticsTools(dataStore));

        return HarnessAgent.builder()
                .name("logistics-agent")
                .description(
                        "Queries shipping nodes, tracking events, estimated arrival times,"
                                + " and whether dates are guaranteed.")
                .sysPrompt(logisticsAgentPrompt())
                .model("dashscope:qwen-plus")
                .toolkit(toolkit)
                .workspace(agentWorkspace("logistics-agent"))
                .middleware(adapter.middleware())
                .maxIters(10)
                .build();
    }

    public HarnessAgent buildAfterSalesAgent(AgentScopeAdapter adapter) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new AfterSalesTools(dataStore));

        return HarnessAgent.builder()
                .name("after-sales-agent")
                .description(
                        "Queries business policies, creates and queries resolution tickets."
                                + " Validates authorization and order version.")
                .sysPrompt(afterSalesAgentPrompt())
                .model("dashscope:qwen-plus")
                .toolkit(toolkit)
                .workspace(agentWorkspace("after-sales-agent"))
                .middleware(adapter.middleware())
                .maxIters(12)
                .build();
    }

    // ---- Service registration helper ------------------------------------------

    /**
     * Register a HarnessAgent with the AgentScope Service and enable Team collaboration.
     *
     * <p>This method:
     * <ol>
     *   <li>Configures {@link AistioConfig} for HTTP self-registration</li>
     *   <li>Sets up {@link HarnessTeamSessionStarter} for Team coordination</li>
     *   <li>Calls {@link Aistio#instrument} to register and start the contract server</li>
     * </ol>
     *
     * @param agent the agent to register
     * @param adapter the adapter (already wired as middleware on the agent)
     * @param agentKey the agent key to register with (e.g. "order-agent")
     * @param teamClient the TeamClient for collaboration (may be null for non-team agents)
     * @return the SessionBridge (call close() on shutdown)
     */
    public static SessionBridge registerWithService(
            HarnessAgent agent, AgentScopeAdapter adapter, String agentKey, TeamClient teamClient) {
        String controlPlaneHttp = envOr("AISTIO_CONTROL_PLANE_HTTP", "http://localhost:8081");
        String internalToken = envOr("BUILDER_INTERNAL_TOKEN", DEFAULT_INTERNAL_TOKEN);
        int contractPort = Integer.parseInt(envOr("AISTIO_CONTRACT_PORT", "18090"));

        // Enable Team collaboration if a TeamClient is provided
        if (teamClient != null) {
            adapter.setTeamSessionStarter(new HarnessTeamSessionStarter(() -> agent, teamClient));
        }

        AistioConfig config =
                AistioConfig.builder(agentKey)
                        .controlPlaneHttp(controlPlaneHttp)
                        .internalToken(internalToken)
                        .namespace("default")
                        .enableEvents(true)
                        .contractHttpPort(contractPort)
                        .startGrpc(false)
                        .startHttp(true)
                        .build();

        SessionBridge bridge = Aistio.instrument(agent, config, adapter);
        log.info("Agent '{}' registered. Contract port: {}", agentKey, bridge.getContractPort());
        return bridge;
    }

    // ---- Business data loading ------------------------------------------------

    public static BusinessDataStore loadDataStore() throws IOException {
        String dataFile = System.getenv("BUSINESS_DATA_FILE");
        if (dataFile != null && !dataFile.isBlank()) {
            return new BusinessDataStore(java.nio.file.Path.of(dataFile));
        }
        InputStream is =
                OrderFulfillmentExample.class
                        .getClassLoader()
                        .getResourceAsStream("documentation2/lrs/business-data.json");
        if (is == null) {
            throw new IOException(
                    "business-data.json not found on classpath at"
                            + " documentation2/lrs/business-data.json");
        }
        return new BusinessDataStore(is);
    }

    // ---- System prompts -------------------------------------------------------

    public static String fulfillmentLeadPrompt() {
        return """
        You are the Fulfillment Lead Agent — the coordinator of an order-fulfillment \
        investigation team.

        Your team members are:
        - order-agent: queries order status, version, and changes
        - inventory-agent: queries warehouse stock levels
        - logistics-agent: queries shipping and delivery status
        - after-sales-agent: queries policies and manages resolution tickets

        Your workflow:
        1. Clarify the customer's order and concern.
        2. Delegate investigation to the appropriate team members \
           (order, inventory, logistics, after-sales) as needed.
        3. All facts must cite their business source, version, or query time. \
           Distinguish between estimated and confirmed information.

        Phase rules:
        - DIAGNOSE phase: only propose solutions. Do NOT modify orders or \
          create resolution tickets.
        - EXECUTE phase: only execute the approved, exact plan. Re-check \
          order version, stock, and delivery feasibility before acting. \
          After business actions, query real results.

        Output a unified report covering:
        - Root cause with evidence
        - Proposed solution and approval requirements
        - Execution results (if in execute phase)
        - Pending items and follow-ups

        If a failure occurs, report what was completed and what was not. \
        Do not repeat writes. Do not treat model output as business authorization.
        """;
    }

    public static String orderAgentPrompt() {
        return """
        You are the Order Agent in an order-fulfillment team.

        Your role is to query order information (status, version, source channel, \
        promised dates) and execute allowed order changes.

        Rules:
        - Always call get_order before reporting any order state.
        - Include the query timestamp and source (OMS) in your response.
        - Never assume an order status — always verify with the tool.
        - When asked to change an order, confirm the current version first.
        - If the order is not found, report that clearly without fabricating data.
        """;
    }

    public static String inventoryAgentPrompt() {
        return """
        You are the Inventory Agent in an order-fulfillment team.

        Your role is to query available stock across warehouses and report per-warehouse \
        quantities, constraints, and transfer conditions.

        Rules:
        - Always call get_inventory before reporting stock levels.
        - Clearly state that query results are a snapshot and do NOT represent reserved \
          or locked inventory.
        - Include the query timestamp and source (WMS) in your response.
        - If a SKU is not found, report that clearly without fabricating data.
        - When comparing warehouses, note the transfer policy for each SKU.
        """;
    }

    public static String logisticsAgentPrompt() {
        return """
        You are the Logistics Agent in an order-fulfillment team.

        Your role is to query logistics status: tracking numbers, transit events, \
        estimated arrival, and whether dates are guaranteed.

        Rules:
        - Always call get_logistics before reporting shipping status.
        - If no tracking number exists, state that clearly — do not fabricate one.
        - Distinguish between estimated and guaranteed delivery dates.
        - Include the query timestamp and source (TMS) in your response.
        - Report all observed transit events in chronological order.
        """;
    }

    public static String afterSalesAgentPrompt() {
        return """
        You are the After-Sales Agent in an order-fulfillment team.

        Your role is to query business policies, create resolution tickets, and check \
        resolution execution status.

        Rules:
        - Before creating any resolution, always:
          1. Call get_policy to check if the action is allowed and whether approval \
             is required.
          2. Confirm the approval record exists — never treat model output like \
             "approved" as real authorization.
          3. Confirm the current order version matches the expected version.
        - When creating a resolution, provide a stable business idempotency key.
        - After creating, call get_resolution to verify real status.
        - Distinguish ACCEPTED (received) from COMPLETED (executed).
        - If a policy disallows the action, report that clearly with the policy reason.
        """;
    }

    // ---- Utility --------------------------------------------------------------

    private static String agentWorkspace(String agentKey) {
        return Paths.get(
                        System.getProperty("user.home"),
                        ".agentscope",
                        "order-fulfillment",
                        agentKey,
                        "workspace")
                .toString();
    }

    public static String envOr(String name, String defaultValue) {
        String v = System.getenv(name);
        return (v != null && !v.isBlank()) ? v : defaultValue;
    }
}
