package com.robert.agent.oauth2.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 登录请求体。
 *
 * @Author: luorenshu
 * @Date: 2026/10/21
 */
@Schema(description = "登录请求")
public record LoginRequest(
        @Schema(description = "用户名", requiredMode = Schema.RequiredMode.REQUIRED, example = "admin")
        String username,

        @Schema(description = "密码", requiredMode = Schema.RequiredMode.REQUIRED, example = "admin123")
        String password
) {
}
