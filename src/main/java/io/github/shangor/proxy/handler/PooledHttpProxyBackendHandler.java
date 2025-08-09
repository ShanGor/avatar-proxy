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
    private volatile boolean connectionReleased = false;

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
        // 区分客户端和远程服务器异常
        if (isRemoteServerException(cause)) {
            logger.warn("Remote server exception: {}", cause.getMessage());
            // 远程服务器关闭连接，释放连接并记录
            releaseConnection();
        } else {
            logger.error("Backend handler exception", cause);
            releaseConnection();
        }
        HttpProxyFrontendHandler.closeOnFlush(ctx.channel());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (logger.isDebugEnabled())
            logger.debug("Backend channel inactive, remote address: {}", ctx.channel().remoteAddress());
        releaseConnection();
        // 只有当客户端连接仍然活跃时才尝试刷新并关闭
        if (inboundChannel.isActive()) {
            HttpProxyFrontendHandler.closeOnFlush(inboundChannel);
        }
    }

    /**
     * 判断是否为远程服务器异常
     * @param cause 异常
     * @return true 如果是远程服务器关闭连接，false 如果是其他异常
     */
    private boolean isRemoteServerException(Throwable cause) {
        // 远程服务器关闭连接通常会抛出这些异常
        return cause instanceof java.io.IOException &&
               (cause.getMessage().contains("Connection reset by peer") ||
                cause.getMessage().contains("Broken pipe") ||
                cause instanceof java.net.ConnectException);
    }

    private void releaseConnection() {
        if (!connectionReleased && channelPool != null && outboundChannel != null) {
            connectionReleased = true;
            // 添加调试信息
            if (logger.isDebugEnabled()) {
                logger.debug("Attempting to release channel {} to pool {}",
                    outboundChannel.id(), channelPool.toString());
            }

            Future<Void> releaseFuture = channelPool.release(outboundChannel);
            releaseFuture.addListener(future -> {
                if (future.isSuccess()) {
                    if (logger.isDebugEnabled())
                        logger.debug("Successfully released connection back to pool");
                } else {
                    // 更详细的错误信息
                    logger.warn("Failed to release connection back to pool: {}. Channel: {}, Pool: {}",
                        future.cause().getMessage(), outboundChannel.id(), channelPool.toString());
                    // 如果连接池释放失败，直接关闭连接
                    if (outboundChannel.isActive()) {
                        if (logger.isDebugEnabled())
                            logger.debug("Closing channel directly due to pool release failure");
                        outboundChannel.close();
                    }
                }
            });
        }
    }
}
