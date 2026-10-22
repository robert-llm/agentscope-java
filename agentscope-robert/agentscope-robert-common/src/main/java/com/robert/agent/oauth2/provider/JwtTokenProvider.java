package com.robert.agent.oauth2.provider;

import com.robert.agent.oauth2.config.OAuth2Properties;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * JWT Token 签发与解析工具。
 *
 * @Author: luorenshu
 * @Date: 2026/10/21
 */
@Component
public class JwtTokenProvider {

    private final JwtEncoder jwtEncoder;
    private final OAuth2Properties oAuth2Properties;

    public JwtTokenProvider(JwtEncoder jwtEncoder, OAuth2Properties oAuth2Properties) {
        this.jwtEncoder = jwtEncoder;
        this.oAuth2Properties = oAuth2Properties;
    }

    /**
     * 生成 JWT Token。
     *
     * @param username 用户名（subject）
     * @param scopes   权限列表
     * @return JWT token 字符串
     */
    public String generateToken(String username, List<String> scopes) {
        Instant now = Instant.now();
        long expirationSeconds = oAuth2Properties.getJwtExpirationSeconds();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(oAuth2Properties.getIssuer())
                .subject(username)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(expirationSeconds))
                .claim("scope", scopes)
                .claim("username", username)
                .build();

        return jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    /**
     * 从 JWT 中提取用户名。
     */
    public String getUsernameFromToken(Jwt jwt) {
        return jwt.getSubject();
    }

    /**
     * 从 JWT 中提取权限列表。
     */
    @SuppressWarnings("unchecked")
    public List<String> getScopesFromToken(Jwt jwt) {
        Object scope = jwt.getClaims().get("scope");
        if (scope instanceof List) {
            return (List<String>) scope;
        }
        return List.of();
    }

    /**
     * 获取 Token 有效期（秒）。
     */
    public long getExpirationSeconds() {
        return oAuth2Properties.getJwtExpirationSeconds();
    }
}
