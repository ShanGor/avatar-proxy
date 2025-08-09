package io.github.shangor.proxy.server;

import io.github.shangor.proxy.config.ProxyConfig;
import io.github.shangor.proxy.handler.HttpProxyInitializer;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpProxyServer {
    private static final Logger log = LoggerFactory.getLogger(HttpProxyServer.class);

    private final ProxyConfig config;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    public HttpProxyServer(ProxyConfig config) {
        this.config = config;
    }

    public void start() throws Exception {
        var factory = NioIoHandler.newFactory();
        bossGroup = new MultiThreadIoEventLoopGroup(1, factory);
        workerGroup = new MultiThreadIoEventLoopGroup(factory);

        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new HttpProxyInitializer(config));

            ChannelFuture f = b.bind(config.getPort()).sync();
            log.info("HTTP Proxy started with port: {}", config.getPort());

            f.channel().closeFuture().sync();
        } finally {
            stop();
        }
    }

    public void stop() {
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        log.info("HTTP proxy server stopped!");
    }
}