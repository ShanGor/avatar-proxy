package io.github.shangor.proxy.handler;

import io.netty.channel.*;
import io.netty.channel.pool.ChannelPool;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PooledHttpProxyBackendHandler extends SimpleChannelInboundHandler<FullHttpResponse> {
    private static final Logger logger = LoggerFactory.getLogger(PooledHttpProxyBackendHandler.class);

    private final Channel inboundChannel;
    private final ChannelPool channelPool;
    private final Channel outboundChannel;

    public PooledHttpProxyBackendHandler(Channel inboundChannel, ChannelPool channelPool, Channel outboundChannel) {
        this.inboundChannel = inboundChannel;
        this.channelPool = channelPool;
        this.outboundChannel = outboundChannel;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse response) {
        response.retain();
        inboundChannel.writeAndFlush(response).addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess()) {
                logger.error("Failed to send response to client", future.cause());
                releaseConnection();
                HttpProxyFrontendHandler.closeOnFlush(ctx.channel());
            } else {
                // 响应发送成功，释放连接回池
                releaseConnection();
            }
        });
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("Backend handler exception", cause);
        releaseConnection();
        HttpProxyFrontendHandler.closeOnFlush(ctx.channel());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        releaseConnection();
        HttpProxyFrontendHandler.closeOnFlush(inboundChannel);
    }
    
    private void releaseConnection() {
        if (channelPool != null && outboundChannel != null) {
            Future<Void> releaseFuture = channelPool.release(outboundChannel);
            releaseFuture.addListener(future -> {
                if (future.isSuccess()) {
                    logger.debug("Successfully released connection back to pool");
                } else {
                    logger.warn("Failed to release connection back to pool", future.cause());
                }
            });
        }
    }
}