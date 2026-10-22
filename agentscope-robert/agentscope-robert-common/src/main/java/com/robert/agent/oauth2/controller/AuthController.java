package com.robert.agent.oauth2.controller;

import com.robert.agent.oauth2.model.LoginRequest;
import com.robert.agent.oauth2.model.LoginResponse;
import com.robert.agent.oauth2.provider.JwtTokenProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 认证接口：提供登录入口，签发 JWT Token。
 *
 * <p>登录接口路径为 {@code /mcp/api/auth/login}，不受 OAuth2 安全过滤器拦截。
 *
 * @Author: luorenshu
 * @Date: 2026/10/21
 */
@RestController
@RequestMapping("/mcp/api/auth")
@Tag(name = "Authentication", description = "认证接口")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final JwtTokenProvider jwtTokenProvider;

    public AuthController(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @PostMapping("/login")
    @Operation(summary = "用户登录", description = "通过用户名密码获取 JWT Token")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        log.info("[OAuth2 登录] username={}", request.username());

        // ============================================================
        // TODO: 接入真实用户认证（如查询数据库校验密码）
        // 当前为演示模式：硬编码校验 admin / admin123
        // ============================================================
        if (!"admin".equals(request.username()) || !"admin123".equals(request.password())) {
            return ResponseEntity.status(401)
                    .body(Map.of("error", "用户名或密码错误"));
        }

        String token = jwtTokenProvider.generateToken(
                request.username(),
                List.of("handbook:read", "handbook:write")
        );

        return ResponseEntity.ok(new LoginResponse(
                token,
                jwtTokenProvider.getExpirationSeconds(),
                request.username()
        ));
    }
}
