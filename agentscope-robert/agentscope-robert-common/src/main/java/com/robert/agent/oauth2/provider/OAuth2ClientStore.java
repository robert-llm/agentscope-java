package com.robert.agent.oauth2.provider;

import com.robert.agent.oauth2.model.OAuth2Client;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OAuth2 客户端存储。
 *
 * <p>支持通过配置文件预注册客户端，也支持运行时动态注册。
 *
 * @Author: luorenshu
 * @Date: 2026/10/23
 */
@Component
@ConfigurationProperties(prefix = "oauth2.clients")
public class OAuth2ClientStore {

    private static final Logger log = LoggerFactory.getLogger(OAuth2ClientStore.class);

    private final Map<String, OAuth2Client> clients = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        log.info("[OAuth2 Client] 已加载 {} 个预注册客户端", clients.size());
        clients.keySet().forEach(id ->
                log.info("  - client_id={}, scopes={}, redirect_uris={}",
                        id, clients.get(id).scopes(), clients.get(id).redirectUris()));
    }

    public OAuth2Client getClient(String clientId) {
        return clients.get(clientId);
    }

    public boolean validateClient(String clientId, String clientSecret) {
        OAuth2Client client = clients.get(clientId);
        return client != null && client.enabled() && client.clientSecret().equals(clientSecret);
    }

    public OAuth2Client registerClient(String clientId, String clientSecret,
                                        List<String> redirectUris, List<String> scopes) {
        OAuth2Client client = new OAuth2Client(clientId, clientSecret, redirectUris, scopes, true);
        clients.put(clientId, client);
        log.info("[OAuth2 Client] 注册新客户端: client_id={}, scopes={}", clientId, scopes);
        return client;
    }

    public List<Map<String, Object>> listClients() {
        return clients.values().stream()
                .map(c -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("client_id", c.clientId());
                    map.put("redirect_uris", c.redirectUris());
                    map.put("scopes", c.scopes());
                    map.put("enabled", c.enabled());
                    return map;
                })
                .toList();
    }

    /**
     * Spring Boot 通过 {@code @ConfigurationProperties} 自动调用此方法注入配置中的客户端列表。
     */
    public void setClients(List<ClientConfig> clientConfigs) {
        if (clientConfigs == null) {
            return;
        }
        for (ClientConfig cfg : clientConfigs) {
            OAuth2Client client = new OAuth2Client(
                    cfg.getClientId(),
                    cfg.getClientSecret(),
                    cfg.getRedirectUris(),
                    cfg.getScopes(),
                    cfg.isEnabled()
            );
            clients.put(cfg.getClientId(), client);
        }
    }

    public static class ClientConfig {
        private String clientId;
        private String clientSecret;
        private List<String> redirectUris = new ArrayList<>();
        private List<String> scopes = new ArrayList<>(List.of("read", "write"));
        private boolean enabled = true;

        public String getClientId() { return clientId; }
        public void setClientId(String clientId) { this.clientId = clientId; }
        public String getClientSecret() { return clientSecret; }
        public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
        public List<String> getRedirectUris() { return redirectUris; }
        public void setRedirectUris(List<String> redirectUris) { this.redirectUris = redirectUris; }
        public List<String> getScopes() { return scopes; }
        public void setScopes(List<String> scopes) { this.scopes = scopes; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
