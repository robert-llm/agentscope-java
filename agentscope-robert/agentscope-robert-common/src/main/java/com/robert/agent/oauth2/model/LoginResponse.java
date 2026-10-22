package com.robert.agent.oauth2.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 登录响应体。
 *
 * @Author: luorenshu
 * @Date: 2026/10/21
 */
@Schema(description = "登录响应")
public record LoginResponse(
        @Schema(description = "JWT Token")
        String token,

        @Schema(description = "Token 类型", example = "Bearer")
        String tokenType,

        @Schema(description = "有效期（秒）", example = "86400")
        long expiresIn,

        @Schema(description = "用户名", example = "admin")
        String username
) {
    public LoginResponse(String token, long expiresIn, String username) {
        this(token, "Bearer", expiresIn, username);
    }
}
