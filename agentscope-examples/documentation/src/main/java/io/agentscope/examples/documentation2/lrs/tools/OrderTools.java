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
 * Tools for the Order Agent ({@code order-agent}).
 *
 * <p>Provides read access to order information including status, version, source channel and
 * promised dates. The order-agent uses these tools to verify the current state of an order before
 * reporting to the fulfillment lead.
 */
public final class OrderTools {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final BusinessDataStore dataStore;

    public OrderTools(BusinessDataStore dataStore) {
        this.dataStore = dataStore;
    }

    @Tool(
            name = "get_order",
            readOnly = true,
            description =
                    """
                    Query an order by its orderId. Returns the order status, version, source \
                    channel, promised arrival date, SKU, quantity, and the timestamp of this \
                    query. Use this before any order modification to confirm the current state \
                    and version. The result includes a 'source' field indicating the system \
                    that provided the data (OMS).\
                    """)
    public String getOrder(
            @ToolParam(name = "orderId", description = "The order identifier, e.g. O-1001")
                    String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return "error: orderId must not be blank";
        }
        JsonNode order = dataStore.getOrder(orderId);
        if (order == null) {
            return "error: order '" + orderId + "' not found in the system";
        }
        ObjectNode result = MAPPER.createObjectNode();
        result.set("order", order);
        result.put("queriedAt", dataStore.nowIso());
        result.put("source", "OMS");
        return result.toString();
    }
}
