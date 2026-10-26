package com.robert.agent.oauth2.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OAuth2 元数据端点（RFC 9728 / RFC 8414）。
 *
 * <p>提供受保护资源元数据和授权服务器元数据的自动发现接口，
 * 供 MCP 客户端（如 WorkBuddy）在收到 401 后自动发现授权流程。
 *
 * @Author: luorenshu
 * @Date: 2026/10/23
 */
@RestController
@Tag(name = "OAuth2 Metadata", description = "OAuth2 保护资源与授权服务器元数据（RFC 9728 / RFC 8414）")
public class OAuth2MetadataController {

    private static final Logger log = LoggerFactory.getLogger(OAuth2MetadataController.class);

    /**
     * 受保护资源元数据（RFC 9728）。
     *
     * <p>客户端通过 401 响应头中的 resource_metadata URL 访问此端点，
     * 发现本资源服务器信任的授权服务器列表。
     */
    @GetMapping(value = "/.well-known/oauth-protected-resource", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "受保护资源元数据",
            description = "RFC 9728：返回本资源服务器的标识和授权服务器发现地址")
    public ResponseEntity<Map<String, Object>> protectedResourceMetadata(HttpServletRequest request) {
        String baseUrl = getBaseUrl(request);
        log.info("[OAuth2 元数据] 受保护资源元数据请求: baseUrl={}", baseUrl);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("resource", baseUrl + "/mcp");
        metadata.put("authorization_servers", List.of(baseUrl));
        metadata.put("bearer_methods_supported", List.of("header"));
        metadata.put("resource_documentation", baseUrl + "/v3/api-docs");

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(metadata);
    }

    /**
     * 举例：workbuddy 拿到本请求返回结果中的，authorization_endpoint后，拼接请求，然后请求：http://localhost:18099/mcp/api/auth/authorize
     * ?response_type
     * =code&client_id=wb_36b889023ff04d53&code_challenge=P3E22-QXDp_GJyGeWg_GulYj6aMiL9R1YN1Td2_XqyQ&code_challenge_method=S256&redirect_uri=workbuddy%3A%2F%2Fworkbuddy%2Fmcp%2Fcustom-mcp%253A%25E5%25BC%2598%25E9%2587%2591%25E5%259C%25B0%25E5%2591%2598%25E5%25B7%25A5%25E5%258A%25A9%25E6%2589%258B%2Foauth%2Fcallback&state=zsMghcqN7_jaYDtQqgnvQ6TsSoUn2_LIkKWixIqvhLU&resource=http%3A%2F%2Flocalhost%3A18099%2Fmcp%2Fapi%2Fhandbook
     * 授权服务器元数据（RFC 8414）。
     *
     * <p>客户端通过受保护资源元数据中的 authorization_servers 地址访问此端点，
     * 获取授权服务器的完整端点信息（authorize、token 等）。
     */
    @GetMapping(value = "/.well-known/oauth-authorization-server", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "授权服务器元数据",
            description = "RFC 8414：返回授权服务器的端点信息，供客户端自动发现授权流程")
    public ResponseEntity<Map<String, Object>> authorizationServerMetadata(HttpServletRequest request) {
        String baseUrl = getBaseUrl(request);
        log.info("[OAuth2 元数据] 授权服务器元数据请求: baseUrl={}", baseUrl);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("issuer", baseUrl);
        metadata.put("authorization_endpoint", baseUrl + "/mcp/api/auth/authorize");
        metadata.put("token_endpoint", baseUrl + "/mcp/api/auth/token");
        metadata.put("registration_endpoint", baseUrl + "/mcp/api/auth/admin/clients");
        metadata.put("token_endpoint_auth_methods_supported", List.of("client_secret_post", "none"));
        metadata.put("response_types_supported", List.of("code"));
        metadata.put("grant_types_supported", List.of("authorization_code", "refresh_token"));
        metadata.put("scopes_supported", List.of("read", "write"));
        metadata.put("code_challenge_methods_supported", List.of("S256", "plain"));

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(metadata);
    }

    private String getBaseUrl(HttpServletRequest request) {
        return request.getScheme() + "://" + request.getServerName()
                + (request.getServerPort() != 80 && request.getServerPort() != 443
                        ? ":" + request.getServerPort() : "");
    }
}
