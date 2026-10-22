package com.robert.agent.oauth2.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * OAuth2 Resource Server 可配置属性。
 *
 * @Author: luorenshu
 * @Date: 2026/10/21
 */
@Component
@ConfigurationProperties(prefix = "oauth2.resource-server")
public class OAuth2Properties {

    /** JWT 签名密钥（至少 32 字符） */
    private String jwtSecret = "agentscope-robert-default-jwt-secret-key-2026-must-be-at-least-256-bits";

    /** Token 有效期（秒），默认 24 小时 */
    private long jwtExpirationSeconds = 86400;

    /** Token 签发者标识 */
    private String issuer = "agentscope-robert";

    /** 是否启用 OAuth2 认证（开发环境可设为 false 跳过认证） */
    private boolean enabled = true;

    public String getJwtSecret() {
        return jwtSecret;
    }

    public void setJwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    public long getJwtExpirationSeconds() {
        return jwtExpirationSeconds;
    }

    public void setJwtExpirationSeconds(long jwtExpirationSeconds) {
        this.jwtExpirationSeconds = jwtExpirationSeconds;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
