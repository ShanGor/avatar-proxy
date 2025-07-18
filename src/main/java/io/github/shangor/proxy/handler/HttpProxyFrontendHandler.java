package io.github.shangor.proxy.handler;

import io.github.shangor.proxy.config.ProxyConfig;
import io.github.shangor.proxy.config.ProxyConfig.RelayProxyConfig;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

public class HttpProxyFrontendHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final Logger log = LoggerFactory.getLogger(HttpProxyFrontendHandler.class);

    private final ProxyConfig config;
    private Channel outboundChannel;

    public HttpProxyFrontendHandler(ProxyConfig config) {
        this.config = config;
    }

    public static final Pattern URI_PATTERN = Pattern.compile("^((?<scheme>https?)://)?(?<host>[a-zA-Z0-9._-]+)(:(?<port>[0-9]+))?(/.*)?$");

    public static URI parseUri(String uri) throws URISyntaxException {
        var matcher = HttpProxyFrontendHandler.URI_PATTERN.matcher(uri);
        if (matcher.matches()) {
            var scheme = matcher.group("scheme");
            var host = matcher.group("host");
            var port = matcher.group("port") != null ? Integer.parseInt(matcher.group("port")) : 80;
            return new URI(scheme, null, host, port, null, null, null);


        } else {
            throw new URISyntaxException("Invalid URI", uri);
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        try {
            // 增加引用计数，防止SimpleChannelInboundHandler自动释放
            request.retain();
            
            log.info("收到请求: {}, {}", request.uri(), request.protocolVersion());
            URI uri = parseUri(request.uri());
            String host = uri.getHost();
            int port = uri.getPort() > 0 ? uri.getPort() : 80;

            // 检查是否需要通过接力代理
            RelayProxyConfig relayConfig = config.getRelayForDomain(host);

            if (relayConfig != null) {
                log.info("使用接力代理 {}:{} 访问 {}", relayConfig.getHost(), relayConfig.getPort(), host);
                connectToRelay(ctx, request, relayConfig, host, port);
            } else {
                log.info("直接连接到目标服务器 {}:{}", host, port);
                connectToTarget(ctx, request, host, port);
            }
        } catch (URISyntaxException e) {
            log.error("URI解析错误", e);
            sendError(ctx, HttpResponseStatus.BAD_REQUEST);
            // 发生异常时释放引用计数
            request.release();
        }
    }

    private void connectToTarget(ChannelHandlerContext ctx, FullHttpRequest request, String host, int port) {
        Bootstrap b = new Bootstrap();
        b.group(ctx.channel().eventLoop())
            .channel(NioSocketChannel.class)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
            .option(ChannelOption.SO_KEEPALIVE, true)
            .handler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline().addLast(
                        new HttpClientCodec(),
                        new HttpObjectAggregator(65536),
                        new HttpProxyBackendHandler(ctx.channel())
                    );
                }
            });

        ChannelFuture f = b.connect(host, port);
        outboundChannel = f.channel();

        f.addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                // 连接成功，发送请求
                // 不需要再次retain，因为在channelRead0中已经调用过
                outboundChannel.writeAndFlush(request);
            } else {
                // 连接失败
                log.error("连接到目标服务器失败: {}:{}", host, port, future.cause());
                sendError(ctx, HttpResponseStatus.BAD_GATEWAY);
                // 释放请求对象
                request.release();
            }
        });
    }

    private void connectToRelay(ChannelHandlerContext ctx, FullHttpRequest request,
                               RelayProxyConfig relayConfig, String targetHost, int targetPort) {
        Bootstrap b = new Bootstrap();
        b.group(ctx.channel().eventLoop())
            .channel(NioSocketChannel.class)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
            .option(ChannelOption.SO_KEEPALIVE, true)
            .handler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline().addLast(
                        new HttpClientCodec(),
                        new HttpObjectAggregator(65536),
                        new HttpProxyBackendHandler(ctx.channel())
                    );
                }
            });

        ChannelFuture f = b.connect(relayConfig.getHost(), relayConfig.getPort());
        outboundChannel = f.channel();

        f.addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                // 连接成功，发送请求到接力代理
                // 修改请求头，添加接力代理信息
                request.headers().set(HttpHeaderNames.HOST, targetHost + ":" + targetPort);
                // 不需要再次retain，因为在channelRead0中已经调用过
                outboundChannel.writeAndFlush(request);
            } else {
                // 连接失败
                log.error("连接到接力代理失败: {}:{}", relayConfig.getHost(), relayConfig.getPort(), future.cause());
                sendError(ctx, HttpResponseStatus.BAD_GATEWAY);
                // 释放请求对象
                request.release();
            }
        });
    }

    private void sendError(ChannelHandlerContext ctx, HttpResponseStatus status) {
        FullHttpResponse response = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1, status, Unpooled.copiedBuffer("Proxy Error: " + status + "\r\n",
            io.netty.util.CharsetUtil.UTF_8));

        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");

        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("前端处理器异常", cause);
        closeOnFlush(ctx.channel());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (outboundChannel != null) {
            closeOnFlush(outboundChannel);
        }
    }

    static void closeOnFlush(Channel ch) {
        if (ch != null && ch.isActive()) {
            ch.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        }
    }
}
