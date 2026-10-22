package com.robert.agent.oauth2.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.util.Map;

/**
 * 403 权限不足处理器。
 *
 * @Author: luorenshu
 * @Date: 2026/10/21
 */
public class AccessDeniedHandlerImpl implements AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(AccessDeniedHandlerImpl.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) {
        log.warn("[OAuth2] 权限不足: uri={}, error={}", request.getRequestURI(), accessDeniedException.getMessage());

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        try {
            MAPPER.writeValue(response.getOutputStream(),
                    Map.of("error", "forbidden",
                            "message", "权限不足，无法访问该资源",
                            "path", request.getRequestURI()));
        } catch (Exception ignored) {
        }
    }
}
