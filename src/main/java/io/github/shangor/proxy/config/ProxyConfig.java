package io.github.shangor.proxy.config;

import java.util.HashMap;
import java.util.Map;

public class ProxyConfig {
    private int port;

    private final int connectTimeoutMillis;
    private Map<String, RelayProxyConfig> domainRelayMap = new HashMap<>();

    public ProxyConfig() {
        this.port = Integer.parseInt(System.getProperty("avatarProxy.port", "3128"));
        this.connectTimeoutMillis = Integer.parseInt(System.getProperty("avatarProxy.connectTimeoutMillis", "10000"));
    }

    public int getConnectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public Map<String, RelayProxyConfig> getDomainRelayMap() {
        return domainRelayMap;
    }

    public void setDomainRelayMap(Map<String, RelayProxyConfig> domainRelayMap) {
        this.domainRelayMap = domainRelayMap;
    }

    public void addDomainRelay(String domain, String relayHost, int relayPort) {
        domainRelayMap.put(domain, new RelayProxyConfig(relayHost, relayPort));
    }

    public RelayProxyConfig getRelayForDomain(String domain) {
        return domainRelayMap.get(domain);
    }

    public record RelayProxyConfig(String host, int port) {
    }
}
