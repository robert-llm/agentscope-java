package com.robert.agent.oauth2.provider;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.robert.agent.oauth2.model.OAuth2Client;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
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
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, OAuth2Client> clients = new ConcurrentHashMap<>();

    /** DCR 动态注册客户端的持久化文件路径 */
    @Value("${oauth2.dcr-persist-file:oauth2-dcr-clients.json}")
    private String dcrPersistFile;

    @PostConstruct
    public void init() {
        log.info("[OAuth2 Client] 已加载 {} 个预注册客户端", clients.size());
        clients.keySet().forEach(id ->
                log.info("  - client_id={}, scopes={}, redirect_uris={}",
                        id, clients.get(id).scopes(), clients.get(id).redirectUris()));

        // 从文件加载 DCR 持久化的客户端
        loadDcrClients();
    }

    /**
     * 从持久化文件加载 DCR 注册的客户端。
     */
    private void loadDcrClients() {
        File file = new File(dcrPersistFile);
        if (!file.exists()) {
            log.info("[OAuth2 Client] DCR 持久化文件不存在，跳过加载");
            return;
        }
        try {
            List<PersistedClient> persisted = MAPPER.readValue(file,
                    new TypeReference<List<PersistedClient>>() {});
            int loaded = 0;
            for (PersistedClient pc : persisted) {
                if (!clients.containsKey(pc.clientId)) {
                    OAuth2Client client = new OAuth2Client(
                            pc.clientId, pc.clientSecret, pc.redirectUris, pc.scopes, true);
                    clients.put(pc.clientId, client);
                    loaded++;
                }
            }
            log.info("[OAuth2 Client] 从持久化文件加载 {} 个 DCR 客户端（共 {} 个）", loaded, clients.size());
        } catch (IOException e) {
            log.warn("[OAuth2 Client] 加载 DCR 持久化文件失败: {}", e.getMessage());
        }
    }

    /**
     * 将当前 DCR 客户端持久化到文件。
     */
    private void persistDcrClients() {
        try {
            List<PersistedClient> persisted = new ArrayList<>();
            for (OAuth2Client client : clients.values()) {
                persisted.add(new PersistedClient(
                        client.clientId(), client.clientSecret(),
                        client.redirectUris(), client.scopes()));
            }
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(new File(dcrPersistFile), persisted);
            log.debug("[OAuth2 Client] DCR 客户端已持久化到 {}（共 {} 个）", dcrPersistFile, persisted.size());
        } catch (IOException e) {
            log.error("[OAuth2 Client] 持久化 DCR 客户端失败: {}", e.getMessage());
        }
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
        // 不允许覆盖已存在的客户端（包括 YAML 预配置的客户端）
        if (clients.containsKey(clientId)) {
            log.warn("[OAuth2 Client] 拒绝注册: client_id={} 已存在，不允许覆盖", clientId);
            return clients.get(clientId);
        }
        OAuth2Client client = new OAuth2Client(clientId, clientSecret, redirectUris, scopes, true);
        clients.put(clientId, client);
        log.info("[OAuth2 Client] 注册新客户端: client_id={}, scopes={}", clientId, scopes);
        // 持久化到文件
        persistDcrClients();
        return client;
    }

    /**
     * 检查指定 client_id 是否存在。
     */
    public boolean hasClient(String clientId) {
        return clients.containsKey(clientId);
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

    /**
     * 持久化用的简化结构（JSON 序列化/反序列化）。
     */
    private static class PersistedClient {
        public String clientId;
        public String clientSecret;
        public List<String> redirectUris;
        public List<String> scopes;

        public PersistedClient() {}

        public PersistedClient(String clientId, String clientSecret,
                               List<String> redirectUris, List<String> scopes) {
            this.clientId = clientId;
            this.clientSecret = clientSecret;
            this.redirectUris = redirectUris;
            this.scopes = scopes;
        }
    }
}
