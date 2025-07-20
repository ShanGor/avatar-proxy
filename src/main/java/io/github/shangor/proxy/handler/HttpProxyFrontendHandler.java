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

import io.github.shangor.proxy.pool.ConnectionPool;
import io.netty.channel.pool.ChannelPool;
import io.netty.util.concurrent.Future;

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

    /**
     * Handle an HTTP and HTTPs request
     * @param ctx           the {@link ChannelHandlerContext} which this {@link SimpleChannelInboundHandler}
     *                      belongs to
     * @param request           the message to handle
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        try {
            // Increase reference count to prevent SimpleChannelInboundHandler from auto-releasing
            request.retain();

            log.info("Got request: {}, {}", request.uri(), request.protocolVersion());

            // Handle CONNECT method (HTTPS proxy)
            if (HttpMethod.CONNECT.equals(request.method())) {
                log.info("HTTPS proxy request {}", request.uri());
                handleConnectRequest(ctx, request);
                return;
            }

            // Handle normal HTTP request
            log.info("HTTP proxy request: {}", request.uri());
            URI uri = parseUri(request.uri(), 80);
            String host = uri.getHost();
            int port = uri.getPort();

            // 检查连接池可用性，实现背压控制
            if (isConnectionPoolExhausted(host, port)) {
                log.warn("Connection pool exhausted for {}:{}, rejecting request", host, port);
                sendError(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE);
                request.release();
                return;
            }

            // Check if relay proxy is needed
            RelayProxyConfig relayConfig = config.getRelayForDomain(host);

            if (relayConfig != null) {
                // 也检查relay连接池
                if (isConnectionPoolExhausted(relayConfig.host(), relayConfig.port())) {
                    log.warn("Relay connection pool exhausted for {}:{}, rejecting request",
                        relayConfig.host(), relayConfig.port());
                    sendError(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE);
                    request.release();
                    return;
                }
                if (log.isDebugEnabled())
                    log.debug("relay proxy {}:{} accessing {}", relayConfig.host(), relayConfig.port(), host);
                connectToRelay(ctx, request, relayConfig, host, port);
            } else {
                if (log.isDebugEnabled())
                    log.debug("direct proxy to {}:{}", host, port);
                connectToTarget(ctx, request, host, port);
            }
        } catch (URISyntaxException e) {
            log.error("URI parse error", e);
            sendError(ctx, HttpResponseStatus.BAD_REQUEST);
            // Release reference count when exception occurs
            request.release();
        }
    }

    /**
     * Handle CONNECT method (HTTPS proxy)
     */
    private void handleConnectRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
        try {
            URI uri = parseUri(request.uri(), 443);

            String host = uri.getHost();
            int port = uri.getPort();

            if (log.isDebugEnabled())
                log.debug("Handling CONNECT request: {}:{}", host, port);

            // Check if relay proxy is needed
            RelayProxyConfig relayConfig = config.getRelayForDomain(host);

            if (relayConfig != null) {
                // 检查relay连接池
                if (isConnectionPoolExhausted(relayConfig.host(), relayConfig.port())) {
                    log.warn("Relay connection pool exhausted for {}:{}, rejecting CONNECT request",
                        relayConfig.host(), relayConfig.port());
                    sendError(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE);
                    request.release();
                    return;
                }
                if (log.isDebugEnabled())
                    log.debug("Relay proxy {}:{} accessing {}:{}", relayConfig.host(), relayConfig.port(), host, port);
                connectToRelayForHttps(ctx, request, relayConfig, host, port);
            } else {
                // 检查目标连接池
                if (isConnectionPoolExhausted(host, port)) {
                    log.warn("Connection pool exhausted for {}:{}, rejecting CONNECT request", host, port);
                    sendError(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE);
                    request.release();
                    return;
                }
                if (log.isDebugEnabled())
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
        // 对于HTTPS代理，如果不是CONNECT方法，也可以使用连接池
        ChannelPool pool = ConnectionPool.getPool(ctx.channel().eventLoop(), host, port, config.getConnectTimeoutMillis());

        Future<Channel> future = pool.acquire();
        future.addListener((Future<Channel> f) -> {
            if (f.isSuccess()) {
                Channel outboundChannel = f.getNow();
                this.outboundChannel = outboundChannel;

                // 添加后端处理器
                ChannelPipeline pipeline = outboundChannel.pipeline();
                pipeline.addLast("backend-handler", new PooledHttpProxyBackendHandler(ctx.channel(), pool, outboundChannel));

                // 发送200 Connection Established响应
                FullHttpResponse response = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1, new HttpResponseStatus(200, "Connection Established"));

                ctx.writeAndFlush(response).addListener((ChannelFutureListener) channelFuture -> {
                    if (channelFuture.isSuccess()) {
                        // 切换到透明代理模式
                        ChannelPipeline clientPipeline = ctx.pipeline();
                        clientPipeline.remove(HttpServerCodec.class);
                        clientPipeline.remove(HttpObjectAggregator.class);
                        clientPipeline.remove(HttpProxyFrontendHandler.class);
                        clientPipeline.addLast(new RelayHandler(outboundChannel));

                        // 服务端也切换到透明模式，但保持连接池管理
                        ChannelPipeline serverPipeline = outboundChannel.pipeline();
                        // 移除HTTP处理器，但保留连接池处理器
                        if (serverPipeline.get(HttpClientCodec.class) != null) {
                            serverPipeline.remove(HttpClientCodec.class);
                        }
                        if (serverPipeline.get(HttpObjectAggregator.class) != null) {
                            serverPipeline.remove(HttpObjectAggregator.class);
                        }
                        serverPipeline.addLast(new RelayHandler(ctx.channel()));
                    } else {
                        pool.release(outboundChannel);
                        closeOnFlush(ctx.channel());
                    }
                });
            } else {
                log.error("Failed to acquire connection from pool for HTTPS {}:{}", host, port, f.cause());
                sendError(ctx, HttpResponseStatus.BAD_GATEWAY);
            }

            request.release();
        });
    }

    private void connectToRelayForHttps(ChannelHandlerContext ctx, FullHttpRequest request,
                                       RelayProxyConfig relayConfig, String targetHost, int targetPort) {
        ChannelPool pool = ConnectionPool.getPool(ctx.channel().eventLoop(), relayConfig.host(), relayConfig.port(), config.getConnectTimeoutMillis());

        Future<Channel> f = pool.acquire();
        // 移除这行错误的代码：outboundChannel = f.channel();

        f.addListener((Future<Channel> future) -> {
            if (future.isSuccess()) {
                Channel outboundChannel = future.getNow();
                this.outboundChannel = outboundChannel;

                HttpRequest connectRequest = new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1, HttpMethod.CONNECT, targetHost + ":" + targetPort);
                connectRequest.headers().set(HttpHeaderNames.HOST, targetHost + ":" + targetPort);

                if (relayConfig.hasAuth()) {
                    String authString = relayConfig.username() + ":" + relayConfig.password();
                    String encodedAuth = java.util.Base64.getEncoder().encodeToString(authString.getBytes());
                    connectRequest.headers().set(HttpHeaderNames.PROXY_AUTHORIZATION, "Basic " + encodedAuth);
                    if (log.isDebugEnabled())
                        log.debug("Added Basic Auth for relay proxy HTTPS connection");
                }

                // Add a handler to process the response from the relay proxy
                ChannelPipeline pipeline = outboundChannel.pipeline();
                pipeline.addLast("proxy-connect-handler", new SimpleChannelInboundHandler<FullHttpResponse>() {
                    @Override
                    protected void channelRead0(ChannelHandlerContext relayCtx, FullHttpResponse msg) throws Exception {
                        if (msg.status().code() == 200) {
                            // Connection to relay is established, now establish connection to client
                            FullHttpResponse response = new DefaultFullHttpResponse(
                                HttpVersion.HTTP_1_1, new HttpResponseStatus(200, "Connection Established"));
                            ctx.writeAndFlush(response).addListener((ChannelFutureListener) clientFuture -> {
                                if (clientFuture.isSuccess()) {
                                    // Switch to transparent proxy mode
                                    ChannelPipeline clientPipeline = ctx.pipeline();
                                    clientPipeline.remove(HttpServerCodec.class);
                                    clientPipeline.remove(HttpObjectAggregator.class);
                                    clientPipeline.remove(HttpProxyFrontendHandler.class);
                                    clientPipeline.addLast(new RelayHandler(outboundChannel));

                                    // Also switch the outbound channel to transparent mode
                                    relayCtx.pipeline().remove(HttpClientCodec.class);
                                    relayCtx.pipeline().remove(HttpObjectAggregator.class);
                                    relayCtx.pipeline().remove(this);
                                    relayCtx.pipeline().addLast(new RelayHandler(ctx.channel()));
                                } else {
                                    pool.release(outboundChannel);
                                    closeOnFlush(ctx.channel());
                                }
                            });
                        } else {
                            log.error("Relay proxy CONNECT request failed with status: {}", msg.status());
                            sendError(ctx, HttpResponseStatus.BAD_GATEWAY);
                            pool.release(outboundChannel);
                        }
                    }

                    @Override
                    public void exceptionCaught(ChannelHandlerContext relayCtx, Throwable cause) {
                        log.error("Exception on relay proxy connection during CONNECT", cause);
                        sendError(ctx, HttpResponseStatus.BAD_GATEWAY);
                        pool.release(outboundChannel);
                    }
                });

                outboundChannel.writeAndFlush(connectRequest).addListener((ChannelFutureListener) proxyFuture -> {
                    if (!proxyFuture.isSuccess()) {
                        log.error("Failed to send CONNECT request to relay proxy", proxyFuture.cause());
                        sendError(ctx, HttpResponseStatus.BAD_GATEWAY);
                        pool.release(outboundChannel);
                    }
                });
            } else {
                log.error("Failed to acquire connection from pool for relay {}:{}", relayConfig.host(), relayConfig.port(), future.cause());
                sendError(ctx, HttpResponseStatus.BAD_GATEWAY);
            }
            request.release();
        });
    }

    private void connectToTarget(ChannelHandlerContext ctx, FullHttpRequest request, String host, int port) {
        ChannelPool pool = ConnectionPool.getPool(ctx.channel().eventLoop(), host, port, config.getConnectTimeoutMillis());

        Future<Channel> future = pool.acquire();
        future.addListener((Future<Channel> f) -> {
            if (f.isSuccess()) {
                Channel outboundChannel = f.getNow();
                this.outboundChannel = outboundChannel;

                // 添加后端处理器
                ChannelPipeline pipeline = outboundChannel.pipeline();
                pipeline.addLast("backend-handler", new PooledHttpProxyBackendHandler(ctx.channel(), pool, outboundChannel));

                // 发送请求
                outboundChannel.writeAndFlush(request).addListener((ChannelFutureListener) sendFuture -> {
                    if (!sendFuture.isSuccess()) {
                        log.error("Failed to send request to target server: {}:{}", host, port, sendFuture.cause());
                        pool.release(outboundChannel);
                        sendError(ctx, HttpResponseStatus.BAD_GATEWAY);
                        request.release();
                    }
                });
            } else {
                log.error("Failed to acquire connection from pool for {}:{}", host, port, f.cause());
                sendError(ctx, HttpResponseStatus.BAD_GATEWAY);
                request.release();
            }
        });
    }

    private void connectToRelay(ChannelHandlerContext ctx, FullHttpRequest request,
                               RelayProxyConfig relayConfig, String targetHost, int targetPort) {
        ChannelPool pool = ConnectionPool.getPool(ctx.channel().eventLoop(), relayConfig.host(), relayConfig.port(), config.getConnectTimeoutMillis());

        Future<Channel> future = pool.acquire();
        future.addListener((Future<Channel> f) -> {
            if (f.isSuccess()) {
                Channel outboundChannel = f.getNow();
                this.outboundChannel = outboundChannel;

                // 修改请求头
                request.headers().set(HttpHeaderNames.HOST, targetHost + ":" + targetPort);

                // 添加Basic Auth if configured
                if (relayConfig.hasAuth()) {
                    String authString = relayConfig.username() + ":" + relayConfig.password();
                    String encodedAuth = java.util.Base64.getEncoder().encodeToString(authString.getBytes());
                    request.headers().set(HttpHeaderNames.PROXY_AUTHORIZATION, "Basic " + encodedAuth);
                    if (log.isDebugEnabled())
                        log.debug("Added Basic Auth for relay proxy");
                }

                // 添加后端处理器
                ChannelPipeline pipeline = outboundChannel.pipeline();
                pipeline.addLast("backend-handler", new PooledHttpProxyBackendHandler(ctx.channel(), pool, outboundChannel));

                // 发送请求
                outboundChannel.writeAndFlush(request).addListener((ChannelFutureListener) sendFuture -> {
                    if (!sendFuture.isSuccess()) {
                        log.error("Failed to send request to relay proxy: {}:{}", relayConfig.host(), relayConfig.port(), sendFuture.cause());
                        pool.release(outboundChannel);
                        sendError(ctx, HttpResponseStatus.BAD_GATEWAY);
                        request.release();
                    }
                });
            } else {
                log.error("Failed to acquire connection from pool for relay {}:{}", relayConfig.host(), relayConfig.port(), f.cause());
                sendError(ctx, HttpResponseStatus.BAD_GATEWAY);
                request.release();
            }
        });
    }

    private boolean isConnectionPoolExhausted(String host, int port) {
        return ConnectionPool.isPoolExhausted(host, port);
    }

    private boolean isConnectionPoolExhausted() {
        return ConnectionPool.isAnyPoolExhausted();
    }

    private Bootstrap createBootstrap(ChannelHandlerContext ctx, ChannelHandler ... handlers) {
        Bootstrap b = new Bootstrap();
        b.group(ctx.channel().eventLoop())
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, config.getConnectTimeoutMillis())
                .option(ChannelOption.SO_KEEPALIVE, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(handlers);
                    }
                });
        return b;
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
        log.error("Frontend handler exception: {}", cause.getMessage());
        if (log.isDebugEnabled() && !"Connection reset".equals(cause.getMessage())) {
            log.debug("Exception details", cause);
        }

        closeOnFlush(ctx.channel());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (outboundChannel != null) {
            // 检查是否是连接池连接
            if (!(outboundChannel.pipeline().get("backend-handler") instanceof PooledHttpProxyBackendHandler)) {
                // 连接池连接会由PooledHttpProxyBackendHandler处理
                // 这里是处理 直接连接，需要手动关闭
                closeOnFlush(outboundChannel);
            }
        }
    }

    static void closeOnFlush(Channel ch) {
        if (ch != null && ch.isActive()) {
            ch.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        }
    }

}


