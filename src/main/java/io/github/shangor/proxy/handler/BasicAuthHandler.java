package io.github.shangor.proxy.handler;

import io.github.shangor.proxy.config.AuthConfig;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.*;
import io.netty.buffer.Unpooled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class BasicAuthHandler extends ChannelInboundHandlerAdapter {
    private static final Logger log = LoggerFactory.getLogger(BasicAuthHandler.class);
    private final AuthConfig authConfig;

    public BasicAuthHandler(AuthConfig authConfig) {
        this.authConfig = authConfig;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof HttpRequest request)) {
            ctx.fireChannelRead(msg);
            return;
        }

        // If authentication is not enabled, pass through directly
        if (!authConfig.isAuthEnabled()) {
            ctx.fireChannelRead(msg);
            return;
        }

        // Get Authorization header
        String authHeader = request.headers().get(HttpHeaderNames.PROXY_AUTHORIZATION);
        if (authHeader == null) {
            authHeader = request.headers().get(HttpHeaderNames.AUTHORIZATION);
        }

        if (authHeader != null && authHeader.startsWith("Basic ")) {
            // Parse Basic Auth credentials
            String base64Credentials = authHeader.substring("Basic ".length());
            String credentials = new String(Base64.getDecoder().decode(base64Credentials), StandardCharsets.UTF_8);
            final String[] values = credentials.split(":", 2);
            if (values.length == 2) {
                String username = values[0];
                String password = values[1];

                // Verify credentials
                if (authConfig.authenticate(username, password)) {
                    if (log.isDebugEnabled())
                        log.debug("Authentication successful for user: {}", username);
                    ctx.fireChannelRead(msg);
                    return;
                }
            }
        }

        // Authentication failed, return 407 response
        log.warn("Authentication failed");
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.PROXY_AUTHENTICATION_REQUIRED,
                Unpooled.copiedBuffer("Proxy Authentication Required", StandardCharsets.UTF_8));

        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        response.headers().set(HttpHeaderNames.PROXY_AUTHENTICATE, "Basic realm=\"Proxy\"");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());

        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }
}