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

    public static URI parseUri(String uri, int defaultPort) throws URISyntaxException {
        var matcher = HttpProxyFrontendHandler.URI_PATTERN.matcher(uri);
        if (matcher.matches()) {
            var scheme = matcher.group("scheme");
            var host = matcher.group("host");
            var portStr = matcher.group("port");

            var port = portStr != null ? Integer.parseInt(portStr) : defaultPort;
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

            log.info("Got request: {}, {}", request.uri(), request.protocolVersion());

            // 处理CONNECT方法（HTTPS代理）
            if (HttpMethod.CONNECT.equals(request.method())) {
                log.info("HTTPS proxy request {}", request.uri());
                handleConnectRequest(ctx, request);
                return;
            }

            // 处理普通HTTP请求
            log.info("HTTP proxy request: {}", request.uri());
            URI uri = parseUri(request.uri(), 80);
            String host = uri.getHost();
            int port = uri.getPort();

            // 检查是否需要通过接力代理
            RelayProxyConfig relayConfig = config.getRelayForDomain(host);

            if (relayConfig != null) {
                log.info("relay proxy {}:{} accessing {}", relayConfig.host(), relayConfig.port(), host);
                connectToRelay(ctx, request, relayConfig, host, port);
            } else {
                log.info("direct proxy to {}:{}", host, port);
                connectToTarget(ctx, request, host, port);
            }
        } catch (URISyntaxException e) {
            log.error("URI parse error", e);
            sendError(ctx, HttpResponseStatus.BAD_REQUEST);
            // 发生异常时释放引用计数
            request.release();
        }
    }

    private void handleConnectRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
        try {
            URI uri = parseUri(request.uri(), 443);

            String host = uri.getHost();
            int port = uri.getPort();

            log.debug("Handling CONNECT request: {}:{}", host, port);

            // 检查是否需要通过接力代理
            RelayProxyConfig relayConfig = config.getRelayForDomain(host);

            if (relayConfig != null) {
                log.debug("Relay proxy {}:{} accessing {}:{}", relayConfig.host(), relayConfig.port(), host, port);
                connectToRelayForHttps(ctx, request, relayConfig, host, port);
            } else {
                log.debug("Direct connect to target server {}:{}", host, port);
                connectToTargetForHttps(ctx, request, host, port);
            }
        } catch (URISyntaxException e) {
            log.error("Invalid CONNECT Request: {}", request.uri());
            sendError(ctx, HttpResponseStatus.BAD_REQUEST);
            request.release();
        }
    }

    private void connectToTargetForHttps(ChannelHandlerContext ctx, FullHttpRequest request, String host, int port) {
        Bootstrap b = new Bootstrap();
        b.group(ctx.channel().eventLoop())
            .channel(NioSocketChannel.class)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
            .option(ChannelOption.SO_KEEPALIVE, true)
            .handler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    // 对于HTTPS，我们不需要HTTP编解码器，直接传输原始数据
                    ch.pipeline().addLast(new RelayHandler(ctx.channel()));
                }
            });

        ChannelFuture f = b.connect(host, port);
        outboundChannel = f.channel();

        f.addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                // 连接成功，发送200 Connection Established
                FullHttpResponse response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, new HttpResponseStatus(200, "Connection Established"));

                // 发送响应后，移除HTTP编解码器，切换到直接传输模式
                ctx.writeAndFlush(response).addListener((ChannelFutureListener) channelFuture -> {
                    if (channelFuture.isSuccess()) {
                        ChannelPipeline pipeline = ctx.pipeline();
                        pipeline.remove(HttpServerCodec.class);
                        pipeline.remove(HttpObjectAggregator.class);
                        pipeline.remove(HttpProxyFrontendHandler.class);
                        pipeline.addLast(new RelayHandler(outboundChannel));
                    } else {
                        closeOnFlush(ctx.channel());
                    }
                });
            } else {
                // 连接失败
                log.error("连接到目标服务器失败: {}:{}", host, port, future.cause());
                sendError(ctx, HttpResponseStatus.BAD_GATEWAY);
            }

            // 释放请求对象
            request.release();
        });
    }

    private void connectToRelayForHttps(ChannelHandlerContext ctx, FullHttpRequest request,
                                       RelayProxyConfig relayConfig, String targetHost, int targetPort) {
        // 实现类似connectToTargetForHttps的逻辑，但连接到接力代理
        Bootstrap b = new Bootstrap();
        b.group(ctx.channel().eventLoop())
            .channel(NioSocketChannel.class)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
            .option(ChannelOption.SO_KEEPALIVE, true)
            .handler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    // 对于HTTPS，我们不需要HTTP编解码器，直接传输原始数据
                    ch.pipeline().addLast(new RelayHandler(ctx.channel()));
                }
            });

        // 连接到接力代理
        ChannelFuture f = b.connect(relayConfig.host(), relayConfig.port());
        outboundChannel = f.channel();

        f.addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                // 连接成功，发送CONNECT请求到接力代理
                HttpRequest connectRequest = new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1, HttpMethod.CONNECT, targetHost + ":" + targetPort);
                connectRequest.headers().set(HttpHeaderNames.HOST, targetHost + ":" + targetPort);

                // 发送CONNECT请求到接力代理
                outboundChannel.writeAndFlush(connectRequest).addListener((ChannelFutureListener) proxyFuture -> {
                    if (proxyFuture.isSuccess()) {
                        // 发送成功，等待接力代理的响应
                        // 这里简化处理，假设接力代理会正确响应
                        // 实际应该添加处理接力代理响应的逻辑

                        // 发送200 Connection Established给客户端
                        FullHttpResponse response = new DefaultFullHttpResponse(
                            HttpVersion.HTTP_1_1, new HttpResponseStatus(200, "Connection Established"));

                        ctx.writeAndFlush(response).addListener((ChannelFutureListener) clientFuture -> {
                            if (clientFuture.isSuccess()) {
                                // 移除HTTP编解码器，切换到直接传输模式
                                ChannelPipeline pipeline = ctx.pipeline();
                                pipeline.remove(HttpServerCodec.class);
                                pipeline.remove(HttpObjectAggregator.class);
                                pipeline.remove(HttpProxyFrontendHandler.class);
                                pipeline.addLast(new RelayHandler(outboundChannel));

                                // 同样修改接力代理连接的管道
                                ChannelPipeline outboundPipeline = outboundChannel.pipeline();
                                outboundPipeline.remove(HttpClientCodec.class);
                                outboundPipeline.remove(HttpObjectAggregator.class);
                            } else {
                                closeOnFlush(ctx.channel());
                            }
                        });
                    } else {
                        log.error("向接力代理发送CONNECT请求失败", proxyFuture.cause());
                        sendError(ctx, HttpResponseStatus.BAD_GATEWAY);
                        closeOnFlush(outboundChannel);
                    }
                });
            } else {
                // 连接失败
                log.error("连接到接力代理失败: {}:{}", relayConfig.host(), relayConfig.port(), future.cause());
                sendError(ctx, HttpResponseStatus.BAD_GATEWAY);
            }

            // 释放请求对象
            request.release();
        });
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

        ChannelFuture f = b.connect(relayConfig.host(), relayConfig.port());
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
                log.error("连接到接力代理失败: {}:{}", relayConfig.host(), relayConfig.port(), future.cause());
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
