package io.github.shangor.proxy.config;

import io.github.shangor.proxy.util.ConnectionPool;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

public class ProxyConfig {
    private int port;
    private final int connectTimeoutMillis;
    private Map<String, RelayProxyConfig> domainRelayMap = new HashMap<>();
    private final AuthConfig authConfig = new AuthConfig();

    public static final Pattern PROXY_PATTERN = Pattern.compile("((?<scheme>(https?)|(socks5))://)?((?<user>[^:@]+):(?<pass>[^:@]+)@)?(?<host>[A-Za-z0-9._-]+):(?<port>[0-9]+)");

    public ProxyConfig() {
        this.port = Integer.parseInt(System.getProperty("avatarProxy.port", "3128"));
        this.connectTimeoutMillis = Integer.parseInt(System.getProperty("avatarProxy.connectTimeoutMillis", "5000"));
        ConnectionPool.setConnectTimeoutMs(this.connectTimeoutMillis);
        ConnectionPool.setMaxConnectionsPerHost(Integer.parseInt(System.getProperty("avatarProxy.maxConnectionsPerHost", "10")));
        ConnectionPool.setIdleTimeoutSeconds(Integer.parseInt(System.getProperty("avatarProxy.idleTimeoutSeconds", "60")));
        var basicAuths = System.getProperty("avatarProxy.basicAuth", null);
        if (basicAuths != null) {
            for (var basicAuth : basicAuths.split(",")) {
                var parts = basicAuth.split(":");
                if (parts.length == 2) {
                    authConfig.addCredential(parts[0].trim(), parts[1].trim());
                }
            }
            authConfig.setAuthEnabled(true);
        } else {
            authConfig.setAuthEnabled(false);
        }

        // avatarProxy.relay=example.com->127.0.0.1:8080,example.com->http://user:pass@127.0.0.1:8080`
        var relayProxyConfig = System.getProperty("avatarProxy.relay", null);
        if (relayProxyConfig != null) {
            var parts = relayProxyConfig.split(",");
            for (var relayProxy : parts) {
                var leftRight = relayProxy.split("->");
                if (leftRight.length == 2) {
                    var domain = leftRight[0].trim();
                    var relayServer = RelayProxyConfig.parse(leftRight[1].trim());
                    if (relayServer != null) {
                        addDomainRelay(relayServer.scheme, domain, relayServer.host(), relayServer.port(), relayServer.username(), relayServer.password());
                    }
                }
            }
        }
    }

    public record RelayProxyConfig(String scheme, String host, int port, String username, String password) {
        public static RelayProxyConfig parse(String relay) {
            var m = PROXY_PATTERN.matcher(relay);
            if (m.matches()) {
                var scheme = m.group("scheme");
                var host = m.group("host");
                var port = Integer.parseInt(m.group("port"));
                var username = m.group("user");
                var password = m.group("pass");
                return new RelayProxyConfig(scheme, host, port, username, password);
            } else {
                return null;
            }
        }
        public boolean hasAuth() {
            return username != null && password != null;
        }
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
        addDomainRelay("http", domain, relayHost, relayPort, null, null);
    }

    public void addDomainRelay(String domain, String relayHost, int relayPort, String username, String password) {
        addDomainRelay("http", domain, relayHost, relayPort, username, password);
    }

    public void addDomainRelay(String scheme, String domain, String relayHost, int relayPort, String username, String password) {
        domainRelayMap.put(domain, new RelayProxyConfig(scheme==null ? "http" : scheme, relayHost, relayPort, username, password));
    }

    public RelayProxyConfig getRelayForDomain(String domain) {
        return domainRelayMap.get(domain);
    }

    public AuthConfig getAuthConfig() {
        return authConfig;
    }

    public void addCredential(String username, String password) {
        authConfig.addCredential(username, password);
    }

    public void enableAuth(boolean enabled) {
        authConfig.setAuthEnabled(enabled);
    }
}
