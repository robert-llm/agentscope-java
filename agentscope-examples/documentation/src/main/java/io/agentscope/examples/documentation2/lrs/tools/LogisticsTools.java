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
package io.agentscope.examples.documentation2.lrs.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.examples.documentation2.lrs.business.BusinessDataStore;

/**
 * Tools for the Logistics Agent ({@code logistics-agent}).
 *
 * <p>Provides read access to shipping status, tracking numbers, transit events, estimated arrival
 * times, and whether dates are guaranteed. The agent must distinguish between estimated and
 * guaranteed delivery dates.
 */
public final class LogisticsTools {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final BusinessDataStore dataStore;

    public LogisticsTools(BusinessDataStore dataStore) {
        this.dataStore = dataStore;
    }

    @Tool(
            name = "get_logistics",
            readOnly = true,
            description =
                    """
                    Query logistics status for an order. Returns the tracking number (or confirms \
                    no tracking number exists), transit events, estimated arrival, candidate \
                    routes with earliest arrival dates, and whether any date is guaranteed. \
                    Use this to assess delivery feasibility. Source: TMS.\
                    """)
    public String getLogistics(
            @ToolParam(name = "orderId", description = "The order identifier, e.g. O-1001")
                    String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return "error: orderId must not be blank";
        }
        JsonNode logistics = dataStore.getLogistics(orderId);
        if (logistics == null) {
            ObjectNode result = MAPPER.createObjectNode();
            result.put("orderId", orderId);
            result.putNull("trackingNumber");
            result.put("hasTrackingNumber", false);
            result.put(
                    "message",
                    "No logistics record found; order may not yet be shipped or does not exist.");
            result.put("queriedAt", dataStore.nowIso());
            result.put("source", "TMS");
            return result.toString();
        }
        ObjectNode result = MAPPER.createObjectNode();
        result.set("logistics", logistics);
        result.put("queriedAt", dataStore.nowIso());
        result.put("source", "TMS");
        return result.toString();
    }
}
