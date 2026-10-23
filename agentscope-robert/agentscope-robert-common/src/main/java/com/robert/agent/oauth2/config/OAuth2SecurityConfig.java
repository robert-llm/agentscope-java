package com.robert.agent.oauth2.config;

import com.robert.agent.oauth2.handler.AccessDeniedHandlerImpl;
import com.robert.agent.oauth2.handler.AuthenticationEntryPointHandler;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.codec.Utf8;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.spec.SecretKeySpec;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OAuth2 Resource Server 安全配置。
 *
 * <p>拦截 {@code /mcp/api/handbook/**} 等受保护路径，天气接口不受影响。
 * 放行 {@code /.well-known/**} OAuth2 元数据端点供客户端自动发现。
 * 当 {@code oauth2.resource-server.enabled=false} 时放行所有请求（开发模式）。
 *
 * @Author: luorenshu
 * @Date: 2026/10/21
 */
@Configuration
@EnableWebSecurity
public class OAuth2SecurityConfig {

    private final OAuth2Properties oAuth2Properties;

    public OAuth2SecurityConfig(OAuth2Properties oAuth2Properties) {
        this.oAuth2Properties = oAuth2Properties;
    }

    /**
     * 让 OAuth2 元数据发现端点和错误页面完全绕过安全过滤链，
     * 避免被 Spring Security 内置的 OAuth2ProtectedResourceMetadataFilter 拦截。
     */
    @Bean
    public WebSecurityCustomizer webSecurityCustomizer() {
        return (web) -> web.ignoring().requestMatchers(
                "/.well-known/**",
                "/error"
        );
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // 开发模式：关闭认证
        if (!oAuth2Properties.isEnabled()) {
            http.securityMatcher("/**")
                    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                    .csrf(csrf -> csrf.disable());
            return http.build();
        }

        http
                .securityMatcher("/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 健康检查无需认证
                        .requestMatchers("/mcp/api/handbook/status").permitAll()
                        // OAuth2 授权端点无需认证
                        .requestMatchers("/mcp/api/auth/authorize", "/mcp/api/auth/token",
                                "/mcp/api/auth/login", "/mcp/api/auth/admin/**").permitAll()
                        // 静态资源（测试页面）
                        .requestMatchers("/oauth2-test.html", "/oauth2-callback.html",
                                "/workbuddy-oauth2-flow.html").permitAll()
                        // 其余接口需要认证
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> {})
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new AuthenticationEntryPointHandler())
                        .accessDeniedHandler(new AccessDeniedHandlerImpl())
                );

        return http.build();
    }

    /**
     * 根据配置密钥生成 HMAC-SHA256 的 SecretKeySpec。
     * 若密钥不足 32 字节则自动补零。
     */
    @Bean
    public SecretKeySpec jwtSecretKeySpec() {
        byte[] keyBytes = Utf8.encode(oAuth2Properties.getJwtSecret());
        if (keyBytes.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(keyBytes, 0, padded, 0, keyBytes.length);
            keyBytes = padded;
        }
        return new SecretKeySpec(keyBytes, "HmacSHA256");
    }

    @Bean
    public JwtDecoder jwtDecoder(SecretKeySpec jwtSecretKeySpec) {
        return NimbusJwtDecoder.withSecretKey(jwtSecretKeySpec)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    @Bean
    public JwtEncoder jwtEncoder(SecretKeySpec jwtSecretKeySpec) {
        // 手动构建 JWT，显式使用 HS256 算法，避免 NimbusJwtEncoder 默认算法不匹配导致 NPE
        return parameters -> {
            try {
                JwtClaimsSet claimsSet = parameters.getClaims();

                // 构建 JWS Header，显式指定 HS256
                JWSHeader jwsHeader = new JWSHeader.Builder(JWSAlgorithm.HS256).build();

                // 将 Spring JwtClaimsSet 转换为 Nimbus JWTClaimsSet
                // 注意：Nimbus 内部 Gson 无法在 Java 17+ 通过反射序列化 Instant，
                // 需将 Instant 转换为 Date，其余类型原样传递
                JWTClaimsSet.Builder nimbusBuilder = new JWTClaimsSet.Builder();
                claimsSet.getClaims().forEach((key, value) -> {
                    if (value instanceof Instant instant) {
                        // iat / exp 等时间字段转为 Date
                        switch (key) {
                            case "iat" -> nimbusBuilder.issueTime(java.util.Date.from(instant));
                            case "exp" -> nimbusBuilder.expirationTime(java.util.Date.from(instant));
                            case "nbf" -> nimbusBuilder.notBeforeTime(java.util.Date.from(instant));
                            default -> nimbusBuilder.claim(key, java.util.Date.from(instant));
                        }
                    } else {
                        nimbusBuilder.claim(key, value);
                    }
                });
                JWTClaimsSet nimbusClaims = nimbusBuilder.build();

                // 签名
                SignedJWT signedJWT = new SignedJWT(jwsHeader, nimbusClaims);
                signedJWT.sign(new MACSigner(jwtSecretKeySpec));
                String tokenValue = signedJWT.serialize();

                // 构建 Spring Security Jwt 对象
                Map<String, Object> headers = new LinkedHashMap<>();
                headers.put("alg", "HS256");
                headers.put("typ", "JWT");

                return new Jwt(
                        tokenValue,
                        claimsSet.getIssuedAt(),
                        claimsSet.getExpiresAt(),
                        headers,
                        claimsSet.getClaims()
                );
            } catch (Exception e) {
                throw new IllegalStateException("JWT 签名失败", e);
            }
        };
    }
}
