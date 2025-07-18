package io.github.shangor.proxy.handler;

import io.github.shangor.proxy.config.ProxyConfig;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;

public class HttpProxyInitializer extends ChannelInitializer<SocketChannel> {

    private final ProxyConfig config;

    public HttpProxyInitializer(ProxyConfig config) {
        this.config = config;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ch.pipeline().addLast(
            new HttpServerCodec(),
            new HttpObjectAggregator(65536),
            new HttpProxyFrontendHandler(config)
        );
    }
}
