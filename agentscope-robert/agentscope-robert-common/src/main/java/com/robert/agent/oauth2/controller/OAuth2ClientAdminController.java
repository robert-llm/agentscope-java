package com.robert.agent.oauth2.controller;

import com.robert.agent.oauth2.model.OAuth2Client;
import com.robert.agent.oauth2.provider.OAuth2ClientStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * OAuth2 客户端管理接口（动态注册新客户端）。
 *
 * @Author: luorenshu
 * @Date: 2026/10/23
 */
@RestController
@RequestMapping("/mcp/api/auth/admin")
@Tag(name = "OAuth2 Client Admin", description = "OAuth2 客户端管理接口（需管理员权限）")
public class OAuth2ClientAdminController {

    private static final Logger log = LoggerFactory.getLogger(OAuth2ClientAdminController.class);

    private final OAuth2ClientStore oAuth2ClientStore;

    public OAuth2ClientAdminController(OAuth2ClientStore oAuth2ClientStore) {
        this.oAuth2ClientStore = oAuth2ClientStore;
    }

    @PostMapping("/clients")
    @Operation(summary = "注册新客户端", description = "生成 client_id 和 client_secret，注册为合法调用方")
    public ResponseEntity<?> registerClient(@RequestBody(required = false) Map<String, Object> request) {
        if (request == null) {
            request = Map.of();
        }

        String clientId = "wb_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String clientSecret = UUID.randomUUID().toString().replace("-", "");

        @SuppressWarnings("unchecked")
        List<String> redirectUris = (List<String>) request.getOrDefault("redirect_uris", List.of());
        @SuppressWarnings("unchecked")
        List<String> scopes = (List<String>) request.getOrDefault("scopes", List.of("read", "write"));

        OAuth2Client client = oAuth2ClientStore.registerClient(clientId, clientSecret, redirectUris, scopes);

        log.info("[OAuth2 Admin] 注册新客户端: client_id={}", clientId);
        return ResponseEntity.ok(Map.of(
                "client_id", client.clientId(),
                "client_secret", client.clientSecret(),
                "redirect_uris", client.redirectUris(),
                "scopes", client.scopes()
        ));
    }

    @GetMapping("/clients")
    @Operation(summary = "查询已注册客户端列表")
    public ResponseEntity<?> listClients() {
        return ResponseEntity.ok(oAuth2ClientStore.listClients());
    }
}
