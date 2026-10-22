package com.robert.agent.oauth2.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Token 端点响应体。
 *
 * @Author: luorenshu
 * @Date: 2026/10/22
 */
@Schema(description = "Token 响应")
public record TokenResponse(
        @Schema(description = "访问令牌")
        @JsonProperty("access_token")
        String accessToken,

        @Schema(description = "令牌类型", example = "Bearer")
        @JsonProperty("token_type")
        String tokenType,

        @Schema(description = "有效期（秒）", example = "3600")
        @JsonProperty("expires_in")
        long expiresIn,

        @Schema(description = "刷新令牌")
        @JsonProperty("refresh_token")
        String refreshToken,

        @Schema(description = "实际授予的权限范围", example = "handbook:read handbook:write")
        String scope,

        @Schema(description = "用户 open_id")
        @JsonProperty("open_id")
        String openId
) {
}
