package io.github.shangor.proxy.config;

import java.util.HashMap;
import java.util.Map;

public class ProxyConfig {
    private int port = 8080;
    private Map<String, RelayProxyConfig> domainRelayMap = new HashMap<>();

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

    public static class RelayProxyConfig {
        private String host;
        private int port;

        public RelayProxyConfig(String host, int port) {
            this.host = host;
            this.port = port;
        }

        public String getHost() {
            return host;
        }

        public int getPort() {
            return port;
        }
    }
}