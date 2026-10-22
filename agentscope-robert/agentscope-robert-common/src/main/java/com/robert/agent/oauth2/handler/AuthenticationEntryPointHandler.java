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
 * @Author: luorenshu
 * @Date: 2026/10/21
 */
public class AuthenticationEntryPointHandler implements AuthenticationEntryPoint {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationEntryPointHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) {
        log.warn("[OAuth2] 认证失败: uri={}, error={}", request.getRequestURI(), authException.getMessage());

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        try {
            MAPPER.writeValue(response.getOutputStream(),
                    Map.of("error", "unauthorized",
                            "message", "未提供有效的认证令牌",
                            "path", request.getRequestURI()));
        } catch (Exception ignored) {
        }
    }
}
