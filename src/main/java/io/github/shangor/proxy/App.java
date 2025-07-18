package io.github.shangor.proxy;

import io.github.shangor.proxy.config.ProxyConfig;
import io.github.shangor.proxy.server.HttpProxyServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class App {
    private static final Logger logger = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) {
        try {
            // 创建代理配置
            ProxyConfig config = new ProxyConfig();
            config.setPort(3128); // 设置代理服务器端口

            // 添加接力代理配置，例如对于example.com域名使用192.168.1.100:8888作为接力代理
            // config.addDomainRelay("example.com", "192.168.1.100", 8888);

            // 启动代理服务器
            HttpProxyServer server = new HttpProxyServer(config);
            server.start();
        } catch (Exception e) {
            logger.error("代理服务器启动失败", e);
        }
    }
}
