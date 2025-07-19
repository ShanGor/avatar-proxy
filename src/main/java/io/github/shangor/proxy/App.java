package io.github.shangor.proxy;

import io.github.shangor.proxy.config.ProxyConfig;
import io.github.shangor.proxy.server.HttpProxyServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class App {
    private static final Logger log = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) {
        try {
            ProxyConfig config = new ProxyConfig();

            HttpProxyServer server = new HttpProxyServer(config);
            server.start();
        } catch (Exception e) {
            log.error("Failed to start proxy server", e);
        }
    }
}
