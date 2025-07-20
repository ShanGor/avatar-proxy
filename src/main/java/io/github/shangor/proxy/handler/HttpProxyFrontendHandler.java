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

import io.github.shangor.proxy.util.ConnectionPool;
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

            // Check if relay proxy is needed
            RelayProxyConfig relayConfig = config.getRelayForDomain(host);

            if (relayConfig != null) {
                log.debug("relay proxy {}:{} accessing {}", relayConfig.host(), relayConfig.port(), host);
                connectToRelay(ctx, request, relayConfig, host, port);
            } else {
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

            log.debug("Handling CONNECT request: {}:{}", host, port);

            // Check if relay proxy is needed
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
        Bootstrap b = createBootstrap(ctx, new HttpClientCodec(),
                new HttpObjectAggregator(65536));

        ChannelFuture f = b.connect(relayConfig.host(), relayConfig.port());
        outboundChannel = f.channel();

        f.addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                HttpRequest connectRequest = new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1, HttpMethod.CONNECT, targetHost + ":" + targetPort);
                connectRequest.headers().set(HttpHeaderNames.HOST, targetHost + ":" + targetPort);

                if (relayConfig.hasAuth()) {
                    String authString = relayConfig.username() + ":" + relayConfig.password();
                    String encodedAuth = java.util.Base64.getEncoder().encodeToString(authString.getBytes());
                    connectRequest.headers().set(HttpHeaderNames.PROXY_AUTHORIZATION, "Basic " + encodedAuth);
                    log.debug("Added Basic Auth for relay proxy HTTPS connection");
                }

                // 创建一个专门的响应处理器
                ChannelInboundHandlerAdapter responseHandler = new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext relayCtx, Object msg) {
                        if (msg instanceof FullHttpResponse proxyResponse) {
                            if (proxyResponse.status().code() == 200) {
                                FullHttpResponse response = new DefaultFullHttpResponse(
                                    HttpVersion.HTTP_1_1, new HttpResponseStatus(200, "Connection Established"));

                                ctx.writeAndFlush(response).addListener((ChannelFutureListener) clientFuture -> {
                                    if (clientFuture.isSuccess()) {
                                        // 客户端侧pipeline修改
                                        ChannelPipeline pipeline = ctx.pipeline();
                                        pipeline.remove(HttpServerCodec.class);
                                        pipeline.remove(HttpObjectAggregator.class);
                                        pipeline.remove(HttpProxyFrontendHandler.class);
                                        pipeline.addLast(new RelayHandler(outboundChannel));

                                        // 服务端侧pipeline修改 - 修复并发问题
                                        ChannelPipeline outboundPipeline = outboundChannel.pipeline();
                                        outboundPipeline.remove(HttpClientCodec.class);
                                        outboundPipeline.remove(HttpObjectAggregator.class);
                                        outboundPipeline.remove(this); // 移除当前响应处理器，而不是HttpProxyFrontendHandler
                                        outboundPipeline.addLast(new RelayHandler(ctx.channel()));
                                    } else {
                                        closeOnFlush(ctx.channel());
                                    }
                                });
                            } else {
                                log.error("Relay proxy response error: {}", proxyResponse.status());
                                sendError(ctx, HttpResponseStatus.BAD_GATEWAY);
                                closeOnFlush(outboundChannel);
                            }
                        } else {
                            log.error("Relay proxy returned unexpected message type: {}", msg.getClass().getName());
                            sendError(ctx, HttpResponseStatus.BAD_GATEWAY);
                            closeOnFlush(outboundChannel);
                        }
                    }

                    @Override
                    public void exceptionCaught(ChannelHandlerContext relayCtx, Throwable cause) {
                        log.error("Exception occurred while processing relay proxy response", cause);
                        sendError(ctx, HttpResponseStatus.BAD_GATEWAY);
                        closeOnFlush(outboundChannel);
                    }
                };

                // 添加响应处理器
                outboundChannel.pipeline().addLast("relay-response-handler", responseHandler);

                // 发送CONNECT请求
                outboundChannel.writeAndFlush(connectRequest).addListener((ChannelFutureListener) proxyFuture -> {
                    if (!proxyFuture.isSuccess()) {
                        log.error("Failed to send CONNECT request to relay proxy", proxyFuture.cause());
                        sendError(ctx, HttpResponseStatus.BAD_GATEWAY);
                        closeOnFlush(outboundChannel);
                    }
                });
            } else {
                log.error("Failed to connect to relay proxy: {}:{}", relayConfig.host(), relayConfig.port(), future.cause());
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
        if (!"Connection reset".equals(cause.getMessage())) {
            log.debug("Exception details", cause);
        }

        closeOnFlush(ctx.channel());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        // 注意：对于HTTPS连接，我们不使用连接池，所以保持原有逻辑
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


