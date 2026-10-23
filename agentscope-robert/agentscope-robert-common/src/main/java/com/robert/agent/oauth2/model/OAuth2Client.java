package com.robert.agent.oauth2.model;

import java.util.List;

/**
 * OAuth2 客户端注册信息。
 *
 * @Author: luorenshu
 * @Date: 2026/10/23
 */
public record OAuth2Client(
        String clientId,
        String clientSecret,
        List<String> redirectUris,
        List<String> scopes,
        boolean enabled
) {

    public boolean isRedirectUriAllowed(String redirectUri) {
        return redirectUris.contains(redirectUri);
    }

    public boolean isScopeAllowed(String scope) {
        if (scope == null || scope.isBlank()) {
            return true;
        }
        List<String> requested = List.of(scope.split("\\s+"));
        return scopes.containsAll(requested);
    }
}
