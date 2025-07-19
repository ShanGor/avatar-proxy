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
        Bootstrap b = createBootstrap(ctx, new RelayHandler(ctx.channel()));

        ChannelFuture f = b.connect(host, port);
        outboundChannel = f.channel();

        f.addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                // Connection successful, send 200 Connection Established
                FullHttpResponse response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, new HttpResponseStatus(200, "Connection Established"));

                // After sending response, remove HTTP codec and switch to direct transmission mode
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
                // Connection failed
                log.error("Failed to connect to target server: {}:{}", host, port, future.cause());
                sendError(ctx, HttpResponseStatus.BAD_GATEWAY);
            }

            // Release request object
            request.release();
        });
    }

    private void connectToRelayForHttps(ChannelHandlerContext ctx, FullHttpRequest request,
                                       RelayProxyConfig relayConfig, String targetHost, int targetPort) {
        // Implement similar logic to connectToTargetForHttps, but connect to relay proxy
        Bootstrap b = createBootstrap(ctx, new HttpClientCodec(),
                new HttpObjectAggregator(65536));

        // Connect to relay proxy
        ChannelFuture f = b.connect(relayConfig.host(), relayConfig.port());
        outboundChannel = f.channel();

        f.addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                // Connection successful, send CONNECT request to relay proxy
                HttpRequest connectRequest = new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1, HttpMethod.CONNECT, targetHost + ":" + targetPort);
                connectRequest.headers().set(HttpHeaderNames.HOST, targetHost + ":" + targetPort);

                // Add Basic Auth if configured
                if (relayConfig.hasAuth()) {
                    String authString = relayConfig.username() + ":" + relayConfig.password();
                    String encodedAuth = java.util.Base64.getEncoder().encodeToString(authString.getBytes());
                    connectRequest.headers().set(HttpHeaderNames.PROXY_AUTHORIZATION, "Basic " + encodedAuth);
                    log.debug("Added Basic Auth for relay proxy HTTPS connection");
                }

                // Send CONNECT request to relay proxy
                outboundChannel.writeAndFlush(connectRequest).addListener((ChannelFutureListener) proxyFuture -> {
                    if (proxyFuture.isSuccess()) {
                        // Send successful, wait for relay proxy response
                        // Add handler for relay proxy response
                        outboundChannel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelRead(ChannelHandlerContext relayCtx, Object msg) {
                                if (msg instanceof FullHttpResponse proxyResponse) {
                                    // Check relay proxy response status
                                    if (proxyResponse.status().code() == 200) {
                                        // Relay proxy connection successful, send 200 Connection Established to client
                                        FullHttpResponse response = new DefaultFullHttpResponse(
                                            HttpVersion.HTTP_1_1, new HttpResponseStatus(200, "Connection Established"));

                                        ctx.writeAndFlush(response).addListener((ChannelFutureListener) clientFuture -> {
                                            if (clientFuture.isSuccess()) {
                                                // Remove HTTP codec, switch to direct transmission mode
                                                ChannelPipeline pipeline = ctx.pipeline();
                                                pipeline.remove(HttpServerCodec.class);
                                                pipeline.remove(HttpObjectAggregator.class);
                                                pipeline.remove(HttpProxyFrontendHandler.class);
                                                pipeline.addLast(new RelayHandler(outboundChannel));

                                                // Also modify relay proxy connection pipeline
                                                ChannelPipeline outboundPipeline = outboundChannel.pipeline();
                                                outboundPipeline.remove(HttpClientCodec.class);
                                                outboundPipeline.remove(HttpObjectAggregator.class);
                                                outboundPipeline.remove(this);
                                                outboundPipeline.addLast(new RelayHandler(ctx.channel()));
                                            } else {
                                                closeOnFlush(ctx.channel());
                                            }
                                        });
                                    } else {
                                        // Relay proxy connection failed
                                        log.error("Relay proxy response error: {}", proxyResponse.status());
                                        sendError(ctx, HttpResponseStatus.BAD_GATEWAY);
                                        closeOnFlush(outboundChannel);
                                    }
                                } else {
                                    // Unexpected message type
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
                        });
                    } else {
                        log.error("Failed to send CONNECT request to relay proxy", proxyFuture.cause());
                        sendError(ctx, HttpResponseStatus.BAD_GATEWAY);
                        closeOnFlush(outboundChannel);
                    }
                });
            } else {
                // Connection failed
                log.error("Failed to connect to relay proxy: {}:{}", relayConfig.host(), relayConfig.port(), future.cause());
                sendError(ctx, HttpResponseStatus.BAD_GATEWAY);
            }

            // Release request object
            request.release();
        });
    }

    private void connectToTarget(ChannelHandlerContext ctx, FullHttpRequest request, String host, int port) {
        Bootstrap b = createBootstrap(ctx, new HttpClientCodec(),
                new HttpObjectAggregator(65536),
                new HttpProxyBackendHandler(ctx.channel()));

        ChannelFuture f = b.connect(host, port);
        outboundChannel = f.channel();

        f.addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                // Connection successful, send request
                // No need to retain again, as it was already called in channelRead0
                outboundChannel.writeAndFlush(request);
            } else {
                // Connection failed
                log.error("Failed to connect to target server: {}:{}", host, port, future.cause());
                sendError(ctx, HttpResponseStatus.BAD_GATEWAY);
                // Release request object
                request.release();
            }
        });
    }

    private void connectToRelay(ChannelHandlerContext ctx, FullHttpRequest request,
                               RelayProxyConfig relayConfig, String targetHost, int targetPort) {
        Bootstrap b = createBootstrap(ctx, new HttpClientCodec(),
                new HttpObjectAggregator(65536),
                new HttpProxyBackendHandler(ctx.channel()));

        ChannelFuture f = b.connect(relayConfig.host(), relayConfig.port());
        outboundChannel = f.channel();

        f.addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                // Connection successful, send request to relay proxy
                // Modify request headers, add relay proxy information
                request.headers().set(HttpHeaderNames.HOST, targetHost + ":" + targetPort);
                
                // Add Basic Auth if configured
                if (relayConfig.hasAuth()) {
                    String authString = relayConfig.username() + ":" + relayConfig.password();
                    String encodedAuth = java.util.Base64.getEncoder().encodeToString(authString.getBytes());
                    request.headers().set(HttpHeaderNames.PROXY_AUTHORIZATION, "Basic " + encodedAuth);
                    log.debug("Added Basic Auth for relay proxy");
                }
                
                // No need to retain again, as it was already called in channelRead0
                outboundChannel.writeAndFlush(request);
            } else {
                // Connection failed
                log.error("Failed to connect to relay proxy: {}:{}", relayConfig.host(), relayConfig.port(), future.cause());
                sendError(ctx, HttpResponseStatus.BAD_GATEWAY);
                // Release request object
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
        log.error("Frontend handler exception", cause);
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
