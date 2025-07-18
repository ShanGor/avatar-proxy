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

            // 添加接力代理配置，例如对于example.com域名使用192.168.1.100:8888作为接力代理
             config.addDomainRelay("google.com", "127.0.0.1", 4780);

            // 启动代理服务器
            HttpProxyServer server = new HttpProxyServer(config);
            server.start();
        } catch (Exception e) {
            log.error("Failed to start proxy server", e);
        }
    }
}
