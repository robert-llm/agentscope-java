package com.robert.agent.oauth2.provider;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Refresh Token 存储（内存实现）。
 *
 * @Author: luorenshu
 * @Date: 2026/10/22
 */
@Component
public class RefreshTokenStore {

    private final Map<String, RefreshTokenInfo> tokenStore = new ConcurrentHashMap<>();

    /**
     * 生成并存储 Refresh Token。
     *
     * @param username 用户
     * @param clientId 应用 ID
     * @param scopes   权限范围
     * @return Refresh Token 字符串
     */
    public String createRefreshToken(String username, String clientId, List<String> scopes) {
        String token = "rt_" + UUID.randomUUID().toString().replace("-", "");
        tokenStore.put(token, new RefreshTokenInfo(username, clientId, scopes));
        return token;
    }

    /**
     * 查询 Refresh Token 信息。
     */
    public RefreshTokenInfo getRefreshTokenInfo(String refreshToken) {
        return tokenStore.get(refreshToken);
    }

    /**
     * 删除 Refresh Token（用于轮换）。
     */
    public void removeRefreshToken(String refreshToken) {
        tokenStore.remove(refreshToken);
    }

    /**
     * Refresh Token 关联信息。
     */
    public record RefreshTokenInfo(
            String username,
            String clientId,
            List<String> scopes
    ) {
    }
}
