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
                // Response sent successfully, release connection back to pool
                releaseConnection();
            }
        });
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        // Distinguish between client and remote server exceptions
        if (isRemoteServerException(cause)) {
            logger.warn("Remote server exception: {}", cause.getMessage());
            // Remote server closed connection, release connection and log
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
        // Only try to flush and close if the inbound channel is still active
        if (inboundChannel.isActive()) {
            HttpProxyFrontendHandler.closeOnFlush(inboundChannel);
        }
    }

    /**
     * Determine if it is a remote server exception
     * @param cause Exception
     * @return true if it is a remote server closing connection, false for other exceptions
     * @return true if it is a remote server closing connection, false if it is another exception
     */
    private boolean isRemoteServerException(Throwable cause) {
        // Remote server closing connection usually throws these exceptions
        return cause instanceof java.io.IOException &&
               (cause.getMessage().contains("Connection reset by peer") ||
                cause.getMessage().contains("Broken pipe") ||
                cause instanceof java.net.ConnectException);
    }

    private void releaseConnection() {
        if (!connectionReleased && channelPool != null && outboundChannel != null) {
            connectionReleased = true;
            // Add debug information
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
                    // More detailed error message
                    logger.warn("Failed to release connection back to pool: {}. Channel: {}, Pool: {}",
                        future.cause().getMessage(), outboundChannel.id(), channelPool.toString());
                    // If connection pool release fails, close the connection directly
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