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
package io.agentscope.examples.documentation2.lrs.business;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * Loads and provides access to the fixed business data for the order-fulfillment example.
 *
 * <p>In a real enterprise deployment this would be replaced by calls to OMS/WMS/TMS APIs. For the
 * example we read from a static JSON file so the tools can return realistic data without any
 * external service dependency.
 */
public final class BusinessDataStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final JsonNode root;

    /**
     * Load from a classpath resource (e.g. {@code business-data.json} in {@code resources/}).
     */
    public BusinessDataStore(InputStream classpathResource) throws IOException {
        try (InputStream in = Objects.requireNonNull(classpathResource, "classpathResource")) {
            this.root = MAPPER.readTree(in.readAllBytes());
        }
    }

    /**
     * Load from a file-system path.
     */
    public BusinessDataStore(Path dataFile) throws IOException {
        this.root = MAPPER.readTree(Files.readString(dataFile));
    }

    // ---- Orders ---------------------------------------------------------------

    public JsonNode getOrder(String orderId) {
        JsonNode orders = root.path("orders");
        if (orders.isArray()) {
            for (JsonNode order : orders) {
                if (order.path("orderId").asText().equals(orderId)) {
                    return order;
                }
            }
        }
        return null;
    }

    // ---- Inventory ------------------------------------------------------------

    public JsonNode getInventory(String sku) {
        JsonNode inventory = root.path("inventory");
        if (inventory.isArray()) {
            for (JsonNode item : inventory) {
                if (item.path("sku").asText().equals(sku)) {
                    return item;
                }
            }
        }
        return null;
    }

    // ---- Logistics ------------------------------------------------------------

    public JsonNode getLogistics(String orderId) {
        JsonNode logistics = root.path("logistics");
        if (logistics.isArray()) {
            for (JsonNode entry : logistics) {
                if (entry.path("orderId").asText().equals(orderId)) {
                    return entry;
                }
            }
        }
        return null;
    }

    // ---- Policies -------------------------------------------------------------

    public JsonNode getPolicy(String actionType) {
        JsonNode policies = root.path("policies");
        if (policies.isArray()) {
            for (JsonNode policy : policies) {
                if (policy.path("action").asText().equals(actionType)) {
                    return policy;
                }
            }
        }
        return null;
    }

    // ---- Resolutions ----------------------------------------------------------

    public JsonNode getResolution(String resolutionId) {
        JsonNode resolutions = root.path("resolutions");
        if (resolutions.isArray()) {
            for (JsonNode res : resolutions) {
                if (res.path("resolutionId").asText().equals(resolutionId)) {
                    return res;
                }
            }
        }
        return null;
    }

    /**
     * Find an existing resolution by idempotency key (server-side dedup).
     */
    public JsonNode findResolutionByIdempotencyKey(String idempotencyKey) {
        JsonNode resolutions = root.path("resolutions");
        if (resolutions.isArray()) {
            for (JsonNode res : resolutions) {
                if (res.path("idempotencyKey").asText().equals(idempotencyKey)) {
                    return res;
                }
            }
        }
        return null;
    }

    public void addResolution(JsonNode resolution) {
        JsonNode resolutions = root.path("resolutions");
        if (resolutions instanceof ArrayNode arrayNode) {
            arrayNode.add(resolution);
        }
    }

    // ---- Utility --------------------------------------------------------------

    public String nowIso() {
        return Instant.now().toString();
    }
}
