package com.robert.agent.oauth2.provider;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 授权码存储（内存实现）。
 *
 * <p>授权码一次性使用，有效期 10 分钟。
 *
 * @Author: luorenshu
 * @Date: 2026/10/22
 */
@Component
public class AuthorizationCodeStore {

    private static final long CODE_EXPIRATION_SECONDS = 600; // 10 分钟

    private final Map<String, AuthorizationCodeInfo> codeStore = new ConcurrentHashMap<>();

    /**
     * 生成并存储授权码。
     *
     * @param clientId    应用 ID
     * @param username    授权用户
     * @param redirectUri 回调地址
     * @param scopes      权限范围
     * @return 授权码
     */
    public String createCode(String clientId, String username, String redirectUri, List<String> scopes) {
        String code = "auth_" + UUID.randomUUID().toString().replace("-", "");
        codeStore.put(code, new AuthorizationCodeInfo(
                clientId, username, redirectUri, scopes,
                Instant.now().plusSeconds(CODE_EXPIRATION_SECONDS)
        ));
        return code;
    }

    /**
     * 消费授权码（一次性），取出后立即删除。
     *
     * @param code        授权码
     * @param clientId    请求方应用 ID
     * @param redirectUri 请求方回调地址
     * @return 授权码信息，不存在或已过期时返回 null
     */
    public AuthorizationCodeInfo consumeCode(String code, String clientId, String redirectUri) {
        AuthorizationCodeInfo info = codeStore.remove(code);
        if (info == null) {
            return null;
        }
        if (Instant.now().isAfter(info.expiresAt())) {
            return null;
        }
        if (!info.clientId().equals(clientId) || !info.redirectUri().equals(redirectUri)) {
            return null;
        }
        return info;
    }

    /**
     * 授权码关联信息。
     */
    public record AuthorizationCodeInfo(
            String clientId,
            String username,
            String redirectUri,
            List<String> scopes,
            Instant expiresAt
    ) {
    }
}
