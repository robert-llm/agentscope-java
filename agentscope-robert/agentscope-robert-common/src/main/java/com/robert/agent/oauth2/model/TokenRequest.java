package com.robert.agent.oauth2.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Token 端点请求体（application/x-www-form-urlencoded）。
 *
 * @Author: luorenshu
 * @Date: 2026/10/22
 */
@Schema(description = "Token 请求")
public record TokenRequest(
        @Schema(description = "授权类型：authorization_code 或 refresh_token", example = "authorization_code")
        String grantType,

        @Schema(description = "授权码（grant_type=authorization_code 时必填）")
        String code,

        @Schema(description = "回调地址（grant_type=authorization_code 时必填）")
        String redirectUri,

        @Schema(description = "刷新令牌（grant_type=refresh_token 时必填）")
        String refreshToken,

        @Schema(description = "应用 ID")
        String clientId,

        @Schema(description = "应用密钥")
        String clientSecret
) {
}
