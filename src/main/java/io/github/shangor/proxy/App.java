package io.github.shangor.proxy;

import io.github.shangor.proxy.config.ProxyConfig;
import io.github.shangor.proxy.server.HttpProxyServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class App {
    private static final Logger log = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) {
        try {
            // Create proxy config, default port is 3128
            ProxyConfig config = new ProxyConfig();

            // Add relay proxy configuration without auth
            config.addDomainRelay("youtube.com", "127.0.0.1", 4780);

            // Add relay proxy configuration with Basic Auth
            config.addDomainRelay("google.com", "127.0.0.1", 4780, "username", "password");

            // Start proxy server
            HttpProxyServer server = new HttpProxyServer(config);
            server.start();
        } catch (Exception e) {
            log.error("Failed to start proxy server", e);
        }
    }
}
