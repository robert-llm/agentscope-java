package com.robert.agent.oauth2.controller;

import com.robert.agent.oauth2.model.TokenResponse;
import com.robert.agent.oauth2.provider.AuthorizationCodeStore;
import com.robert.agent.oauth2.provider.AuthorizationCodeStore.AuthorizationCodeInfo;
import com.robert.agent.oauth2.provider.JwtTokenProvider;
import com.robert.agent.oauth2.provider.RefreshTokenStore;
import com.robert.agent.oauth2.provider.RefreshTokenStore.RefreshTokenInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * OAuth2 授权端点：实现标准授权码流程。
 * <ul>
 *     完整的授权流程
 *  <li>
 *      1. 客户端引导用户浏览器访问：
 *      GET /mcp/api/auth/authorize?response_type=code&client_id=xxx&redirect_uri=xxx&scope=xxx&state=xxx
 *  </li>
 *
 *  <li> 2. 用户看到授权确认页，点击「同意授权」</li>
 *
 *  <li> 3. 服务端重定向至 redirect_uri，携带授权码：
 *      GET https://example.com/callback?code=auth_xxx&state=xxx
 *  </li>
 *  <li>
 *    4. 客户端后端用授权码换取 Token：
 *     POST /mcp/api/auth/token
 *     Content-Type: application/x-www-form-urlencoded
 *     grant_type=authorization_code&code=xxx&redirect_uri=xxx&client_id=xxx&client_secret=xxx
 *  </li>
 *  <li>
 *    5. 返回 access_token + refresh_token：
 *    { "access_token": "eyJ...", "token_type": "Bearer", "expires_in": 86400,
 *      "refresh_token": "rt_xxx", "scope": "handbook:read handbook:write", "open_id": "admin" }
 *  </li>
 *  <li>
 *      6. Token 过期后，用 refresh_token 刷新：
 *    POST /mcp/api/auth/token
 *    grant_type=refresh_token&refresh_token=rt_xxx&client_id=xxx&client_secret=xxx
 *  </li>
 *  </li>

 *  </li>
 * </ul>
 *
 * <ul>
 *   <li>GET  /authorize — 请求用户授权，展示授权确认页</li>
 *   <li>POST /authorize — 用户确认授权，生成授权码并重定向</li>
 *   <li>POST /token     — 换取接口调用凭证 / 刷新 Access Token</li>
 * </ul>
 *
 * @Author: luorenshu
 * @Date: 2026/10/22
 */
@RestController
@RequestMapping("/mcp/api/auth")
@Tag(name = "OAuth2", description = "OAuth2 授权码流程接口")
public class OAuth2Controller {

    private static final Logger log = LoggerFactory.getLogger(OAuth2Controller.class);

    private final AuthorizationCodeStore authorizationCodeStore;
    private final RefreshTokenStore refreshTokenStore;
    private final JwtTokenProvider jwtTokenProvider;

    public OAuth2Controller(AuthorizationCodeStore authorizationCodeStore,
                            RefreshTokenStore refreshTokenStore,
                            JwtTokenProvider jwtTokenProvider) {
        this.authorizationCodeStore = authorizationCodeStore;
        this.refreshTokenStore = refreshTokenStore;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    // ==================== 请求用户授权 ====================

    @GetMapping("/authorize")
    @Operation(summary = "请求用户授权",
            description = "引导用户在浏览器中访问授权页，用户确认后重定向至 redirect_uri 并携带授权码")
    public ResponseEntity<String> authorize(
            @RequestParam("response_type") String responseType,
            @RequestParam("client_id") String clientId,
            @RequestParam("redirect_uri") String redirectUri,
            @RequestParam(value = "scope", required = false) String scope,
            @RequestParam(value = "state", required = false) String state) {

        log.info("[OAuth2 授权] response_type={}, client_id={}, redirect_uri={}, scope={}",
                responseType, clientId, redirectUri, scope);

        if (!"code".equals(responseType)) {
            return ResponseEntity.badRequest().body("{\"error\":\"unsupported_response_type\"}");
        }

        // 解析 scope
        List<String> scopes = (scope != null && !scope.isBlank())
                ? List.of(scope.split("\\s+"))
                : List.of("handbook:read", "handbook:write");

        // 展示授权确认页
        String html = buildConsentPageHtml(clientId, redirectUri, scopes, state);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(html);
    }

    @PostMapping(value = "/authorize", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    @Operation(summary = "用户确认授权",
            description = "用户确认授权后，生成授权码并重定向至 redirect_uri")
    public ResponseEntity<Void> approveAuthorization(
            @RequestParam("approved") String approved,
            @RequestParam("client_id") String clientId,
            @RequestParam("redirect_uri") String redirectUri,
            @RequestParam(value = "scope", required = false) String scope,
            @RequestParam(value = "state", required = false) String state) {

        log.info("[OAuth2 授权确认] approved={}, client_id={}", approved, clientId);

        if (!"true".equals(approved)) {
            // 用户拒绝授权
            String separator = redirectUri.contains("?") ? "&" : "?";
            String location = redirectUri + separator + "error=access_denied"
                    + (state != null ? "&state=" + URLEncoder.encode(state, StandardCharsets.UTF_8) : "");
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header(HttpHeaders.LOCATION, location)
                    .build();
        }

        // 用户同意授权，生成授权码
        // TODO: 从当前登录会话获取用户名，此处演示使用固定用户
        String username = "admin";
        List<String> scopes = (scope != null && !scope.isBlank())
                ? List.of(scope.split("\\s+"))
                : List.of("handbook:read", "handbook:write");

        String code = authorizationCodeStore.createCode(clientId, username, redirectUri, scopes);

        // 重定向至 redirect_uri，携带 code 和 state
        String separator = redirectUri.contains("?") ? "&" : "?";
        String location = redirectUri + separator
                + "code=" + URLEncoder.encode(code, StandardCharsets.UTF_8)
                + (state != null ? "&state=" + URLEncoder.encode(state, StandardCharsets.UTF_8) : "");

        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, location)
                .build();
    }

    // ==================== 换取 / 刷新 Token ====================

    @PostMapping(value = "/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    @Operation(summary = "换取接口调用凭证",
            description = "使用授权码或 refresh_token 换取 access_token")
    public ResponseEntity<?> token(
            @RequestParam("grant_type") String grantType,
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "redirect_uri", required = false) String redirectUri,
            @RequestParam(value = "refresh_token", required = false) String refreshToken,
            @RequestParam("client_id") String clientId,
            @RequestParam("client_secret") String clientSecret) {

        log.info("[OAuth2 Token] grant_type={}, client_id={}", grantType, clientId);

        // ============================================================
        // TODO: 校验 clientId 和 clientSecret（查数据库或配置）
        // 当前为演示模式：任意非空值均通过
        // ============================================================
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "invalid_client",
                    "error_description", "client_id 和 client_secret 不能为空"));
        }

        return switch (grantType) {
            case "authorization_code" -> handleAuthorizationCode(code, redirectUri, clientId, clientSecret);
            case "refresh_token" -> handleRefreshToken(refreshToken, clientId, clientSecret);
            default -> ResponseEntity.badRequest().body(Map.of(
                    "error", "unsupported_grant_type",
                    "error_description", "不支持的 grant_type: " + grantType));
        };
    }

    // ==================== 内部方法 ====================

    /**
     * 授权码换取 Token。
     */
    private ResponseEntity<?> handleAuthorizationCode(
            String code, String redirectUri, String clientId, String clientSecret) {

        if (code == null || code.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "invalid_request", "error_description", "code 不能为空"));
        }
        if (redirectUri == null || redirectUri.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "invalid_request", "error_description", "redirect_uri 不能为空"));
        }

        AuthorizationCodeInfo codeInfo = authorizationCodeStore.consumeCode(code, clientId, redirectUri);
        if (codeInfo == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "invalid_grant", "error_description", "授权码无效或已过期"));
        }

        return ResponseEntity.ok(buildTokenResponse(codeInfo.username(), codeInfo.scopes(), clientId));
    }

    /**
     * 刷新 Token。
     */
    private ResponseEntity<?> handleRefreshToken(
            String refreshToken, String clientId, String clientSecret) {

        if (refreshToken == null || refreshToken.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "invalid_request", "error_description", "refresh_token 不能为空"));
        }

        RefreshTokenInfo rtInfo = refreshTokenStore.getRefreshTokenInfo(refreshToken);
        if (rtInfo == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "invalid_grant", "error_description", "refresh_token 无效"));
        }
        if (!rtInfo.clientId().equals(clientId)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "invalid_grant", "error_description", "client_id 不匹配"));
        }

        // 轮换 refresh_token：删除旧的，生成新的
        refreshTokenStore.removeRefreshToken(refreshToken);

        return ResponseEntity.ok(buildTokenResponse(rtInfo.username(), rtInfo.scopes(), clientId));
    }

    /**
     * 构建 Token 响应（含 access_token + refresh_token）。
     */
    private TokenResponse buildTokenResponse(String username, List<String> scopes, String clientId) {
        String accessToken = jwtTokenProvider.generateToken(username, scopes);
        String newRefreshToken = refreshTokenStore.createRefreshToken(username, clientId, scopes);
        String scopeStr = String.join(" ", scopes);

        return new TokenResponse(
                accessToken,
                "Bearer",
                jwtTokenProvider.getExpirationSeconds(),
                newRefreshToken,
                scopeStr,
                username  // open_id
        );
    }

    /**
     * 构建授权确认页 HTML。
     */
    private String buildConsentPageHtml(String clientId, String redirectUri,
                                        List<String> scopes, String state) {
        String scopeListHtml = scopes.stream()
                .map(s -> "<li>" + escapeHtml(s) + "</li>")
                .reduce("", (a, b) -> a + b);

        return "<!DOCTYPE html>\n"
                + "<html lang=\"zh\">\n"
                + "<head><meta charset=\"UTF-8\"><title>授权确认</title>\n"
                + "<style>"
                + "body{font-family:sans-serif;max-width:480px;margin:60px auto;padding:20px;text-align:center}"
                + ".card{border:1px solid #ddd;border-radius:12px;padding:32px;background:#fafafa}"
                + "h2{margin-bottom:16px}"
                + ".scopes{text-align:left;margin:16px 0;padding:0 20px}"
                + ".scopes li{margin:6px 0;color:#555}"
                + ".btn{padding:10px 32px;border:none;border-radius:6px;cursor:pointer;margin:8px;font-size:15px}"
                + ".btn-approve{background:#16a34a;color:#fff}"
                + ".btn-deny{background:#dc2626;color:#fff}"
                + "</style></head>\n"
                + "<body><div class=\"card\">\n"
                + "<h2>应用授权确认</h2>\n"
                + "<p>应用 <strong>" + escapeHtml(clientId) + "</strong> 请求获取以下权限：</p>\n"
                + "<ul class=\"scopes\">" + scopeListHtml + "</ul>\n"
                + "<form method=\"POST\" action=\"/mcp/api/auth/authorize\">\n"
                + "  <input type=\"hidden\" name=\"client_id\" value=\"" + escapeHtml(clientId) + "\">\n"
                + "  <input type=\"hidden\" name=\"redirect_uri\" value=\"" + escapeHtml(redirectUri) + "\">\n"
                + (state != null ? "  <input type=\"hidden\" name=\"state\" value=\"" + escapeHtml(state) + "\">\n" : "")
                + "  <input type=\"hidden\" name=\"scope\" value=\"" + escapeHtml(String.join(" ", scopes)) + "\">\n"
                + "  <button type=\"submit\" name=\"approved\" value=\"true\" class=\"btn btn-approve\">同意授权</button>\n"
                + "  <button type=\"submit\" name=\"approved\" value=\"false\" class=\"btn btn-deny\">拒绝</button>\n"
                + "</form>\n"
                + "</div></body></html>";
    }

    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
