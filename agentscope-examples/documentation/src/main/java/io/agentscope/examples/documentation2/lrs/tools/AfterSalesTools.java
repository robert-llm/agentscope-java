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
import java.util.UUID;

/**
 * Tools for the After-Sales Agent ({@code after-sales-agent}).
 *
 * <p>Provides access to business policies, resolution creation, and resolution status queries. The
 * agent must validate authorization, order version, and idempotency before creating any resolution.
 */
public final class AfterSalesTools {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final BusinessDataStore dataStore;

    public AfterSalesTools(BusinessDataStore dataStore) {
        this.dataStore = dataStore;
    }

    @Tool(
            name = "get_policy",
            readOnly = true,
            description =
                    """
                    Query the business policy for a proposed action on an order (e.g. \
                    'warehouse_transfer', 'expedite_shipment', 'cancel_and_refund'). Returns \
                    whether the action is allowed, whether approval is required, the approval \
                    level, and the policy version. Use before creating any resolution. \
                    Source: AFTER_SALES_POLICY.\
                    """)
    public String getPolicy(
            @ToolParam(name = "orderId", description = "The order identifier") String orderId,
            @ToolParam(
                            name = "action",
                            description =
                                    "The proposed action, e.g. warehouse_transfer, "
                                            + "expedite_shipment, cancel_and_refund")
                    String action) {
        if (orderId == null || orderId.isBlank()) {
            return "error: orderId must not be blank";
        }
        if (action == null || action.isBlank()) {
            return "error: action must not be blank";
        }
        JsonNode order = dataStore.getOrder(orderId);
        if (order == null) {
            return "error: order '" + orderId + "' not found; cannot evaluate policy";
        }
        JsonNode policy = dataStore.getPolicy(action);
        ObjectNode result = MAPPER.createObjectNode();
        result.put("orderId", orderId);
        result.put("action", action);
        result.put("orderStatus", order.path("status").asText());
        if (policy == null) {
            result.put("allowed", false);
            result.put("reason", "No policy defined for action '" + action + "'");
        } else {
            result.set("policy", policy);
        }
        result.put("queriedAt", dataStore.nowIso());
        result.put("source", "AFTER_SALES_POLICY");
        return result.toString();
    }

    @Tool(
            name = "create_resolution",
            description =
                    """
                    Create an after-sales resolution (处理单) for an order. The business service \
                    validates authorization, order version, and the action against the approval \
                    record. Returns a real resolution ID. The caller must provide a stable \
                    business idempotency key to prevent duplicate creation. If a resolution with \
                    the same idempotency key already exists, the existing resolution is returned \
                    without creating a new one. Source: AFTER_SALES_SYSTEM.\
                    """)
    public String createResolution(
            @ToolParam(name = "orderId", description = "The order identifier") String orderId,
            @ToolParam(name = "action", description = "The resolution action to perform")
                    String action,
            @ToolParam(
                            name = "expectedOrderVersion",
                            description =
                                    "The order version the caller expects; the server validates "
                                            + "this against the current version")
                    int expectedOrderVersion,
            @ToolParam(
                            name = "approvalRecord",
                            description =
                                    "Reference to the approval that authorizes this action "
                                            + "(e.g. approval ticket ID or approver name)")
                    String approvalRecord,
            @ToolParam(
                            name = "idempotency_key",
                            description =
                                    "Stable business idempotency key to prevent duplicate creation."
                                            + " Must be unique per business intent.")
                    String idempotencyKey) {
        if (orderId == null || orderId.isBlank()) {
            return "error: orderId must not be blank";
        }
        if (action == null || action.isBlank()) {
            return "error: action must not be blank";
        }
        if (approvalRecord == null || approvalRecord.isBlank()) {
            return "error: approvalRecord is required; cannot create resolution without approval";
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return "error: idempotency_key is required";
        }

        // Idempotency check: if a resolution with this key already exists, return it.
        JsonNode existing = dataStore.findResolutionByIdempotencyKey(idempotencyKey);
        if (existing != null) {
            ObjectNode result = MAPPER.createObjectNode();
            result.put("resolutionId", existing.path("resolutionId").asText());
            result.put("status", existing.path("status").asText());
            result.put("orderId", orderId);
            result.put("action", action);
            result.put("idempotent", true);
            result.put(
                    "message",
                    "A resolution with this idempotency key already exists. "
                            + "No new resolution was created.");
            result.put("source", "AFTER_SALES_SYSTEM");
            return result.toString();
        }

        JsonNode order = dataStore.getOrder(orderId);
        if (order == null) {
            return "error: order '" + orderId + "' not found";
        }
        int currentVersion = order.path("version").asInt(0);
        if (currentVersion != expectedOrderVersion) {
            return "error: order version mismatch — expected "
                    + expectedOrderVersion
                    + " but current is "
                    + currentVersion
                    + ". Re-read the order and re-confirm before retrying.";
        }

        String resolutionId = "RES-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        ObjectNode resolution = MAPPER.createObjectNode();
        resolution.put("resolutionId", resolutionId);
        resolution.put("orderId", orderId);
        resolution.put("action", action);
        resolution.put("orderVersion", currentVersion);
        resolution.put("approvalRecord", approvalRecord);
        resolution.put("idempotencyKey", idempotencyKey);
        resolution.put("status", "ACCEPTED");
        resolution.put("createdAt", dataStore.nowIso());
        dataStore.addResolution(resolution);

        ObjectNode result = MAPPER.createObjectNode();
        result.put("resolutionId", resolutionId);
        result.put("status", "ACCEPTED");
        result.put("orderId", orderId);
        result.put("action", action);
        result.put("createdAt", resolution.path("createdAt").asText());
        result.put("source", "AFTER_SALES_SYSTEM");
        return result.toString();
    }

    @Tool(
            name = "get_resolution",
            readOnly = true,
            description =
                    """
                    Query the status of an after-sales resolution by its resolution ID. Returns \
                    the execution result, distinguishing ACCEPTED (received but not yet completed) \
                    from COMPLETED (fully executed). Source: AFTER_SALES_SYSTEM.\
                    """)
    public String getResolution(
            @ToolParam(name = "resolutionId", description = "The resolution identifier")
                    String resolutionId) {
        if (resolutionId == null || resolutionId.isBlank()) {
            return "error: resolutionId must not be blank";
        }
        JsonNode resolution = dataStore.getResolution(resolutionId);
        if (resolution == null) {
            return "error: resolution '" + resolutionId + "' not found";
        }
        ObjectNode result = MAPPER.createObjectNode();
        result.set("resolution", resolution);
        result.put("queriedAt", dataStore.nowIso());
        result.put("source", "AFTER_SALES_SYSTEM");
        return result.toString();
    }
}
