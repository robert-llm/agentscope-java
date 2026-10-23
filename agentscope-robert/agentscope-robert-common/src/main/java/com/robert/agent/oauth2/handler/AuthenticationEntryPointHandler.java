package com.robert.agent.oauth2.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.util.Map;

/**
 * 401 未认证异常处理器。
 *
 * <p>返回 401 时附带 {@code WWW-Authenticate: Bearer resource_metadata="..."} 头，
 * 引导 MCP 客户端（如 WorkBuddy）通过元数据端点自动发现授权服务器。
 *
 * @Author: luorenshu
 * @Date: 2026/10/21
 */
public class AuthenticationEntryPointHandler implements AuthenticationEntryPoint {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationEntryPointHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) {
        // 记录请求头信息，便于排查客户端是否正确携带 Token
        String authHeader = request.getHeader("Authorization");
        log.warn("[OAuth2] 认证失败: uri={}, method={}, error={}, Authorization={}",
                request.getRequestURI(), request.getMethod(), authException.getMessage(),
                authHeader != null ? (authHeader.substring(0, Math.min(authHeader.length(), 30)) + "...") : "null");

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        // 构建 resource_metadata URL，写入 WWW-Authenticate 头（RFC 9728）
        String baseUrl = getBaseUrl(request);
        String resourceMetadataUrl = baseUrl + "/.well-known/oauth-protected-resource";
        response.setHeader("WWW-Authenticate",
                "Bearer resource_metadata=\"" + resourceMetadataUrl + "\"");

        try {
            MAPPER.writeValue(response.getOutputStream(),
                    Map.of("error", "unauthorized",
                            "message", "未提供有效的认证令牌，请先通过 OAuth2 授权获取 access_token",
                            "resource_metadata", resourceMetadataUrl,
                            "path", request.getRequestURI()));
        } catch (Exception ignored) {
        }
    }

    private String getBaseUrl(HttpServletRequest request) {
        return request.getScheme() + "://" + request.getServerName()
                + (request.getServerPort() != 80 && request.getServerPort() != 443
                        ? ":" + request.getServerPort() : "");
    }
}
