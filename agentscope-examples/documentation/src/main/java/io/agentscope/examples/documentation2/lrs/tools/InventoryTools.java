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
 * Tools for the Inventory Agent ({@code inventory-agent}).
 *
 * <p>Provides read access to warehouse stock levels. The agent must clearly communicate that query
 * results are a snapshot and do NOT represent reserved or locked inventory.
 */
public final class InventoryTools {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final BusinessDataStore dataStore;

    public InventoryTools(BusinessDataStore dataStore) {
        this.dataStore = dataStore;
    }

    @Tool(
            name = "get_inventory",
            readOnly = true,
            description =
                    """
                    Query available stock for a SKU across warehouses. Returns per-warehouse \
                    available quantity, reserved quantity, capacity constraints, and the transfer \
                    policy. The result is a snapshot — it does NOT reserve or lock inventory. \
                    When a 'quantity' is provided, the tool also reports whether total available \
                    stock is sufficient. Source: WMS.\
                    """)
    public String getInventory(
            @ToolParam(name = "sku", description = "The product SKU to check, e.g. SKU-A100")
                    String sku,
            @ToolParam(
                            name = "quantity",
                            description =
                                    "The requested quantity; used to assess whether total available"
                                            + " stock is sufficient",
                            required = false)
                    Integer quantity) {
        if (sku == null || sku.isBlank()) {
            return "error: sku must not be blank";
        }
        JsonNode inventory = dataStore.getInventory(sku);
        if (inventory == null) {
            return "error: no inventory record found for SKU '" + sku + "'";
        }
        ObjectNode result = MAPPER.createObjectNode();
        result.set("inventory", inventory);
        result.put("queriedAt", dataStore.nowIso());
        result.put("source", "WMS");
        result.put(
                "disclaimer",
                "This is a snapshot. Query results do NOT represent reserved or locked inventory.");

        if (quantity != null) {
            int totalAvailable = 0;
            JsonNode warehouses = inventory.path("warehouses");
            if (warehouses.isArray()) {
                for (JsonNode wh : warehouses) {
                    totalAvailable += wh.path("available").asInt(0);
                }
            }
            result.put("requestedQuantity", quantity);
            result.put("totalAvailable", totalAvailable);
            result.put("sufficient", totalAvailable >= quantity);
        }
        return result.toString();
    }
}
