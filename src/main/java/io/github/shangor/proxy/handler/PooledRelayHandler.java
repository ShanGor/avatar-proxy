package io.github.shangor.proxy.handler;

import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.pool.ChannelPool;
import io.netty.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PooledRelayHandler extends ChannelInboundHandlerAdapter {
    private static final Logger log = LoggerFactory.getLogger(PooledRelayHandler.class);

    private final Channel relayChannel;
    private final ChannelPool channelPool;
    private final Channel outboundChannel;
    private volatile boolean connectionReleased = false;

    public PooledRelayHandler(Channel relayChannel, ChannelPool channelPool, Channel outboundChannel) {
        this.relayChannel = relayChannel;
        this.channelPool = channelPool;
        this.outboundChannel = outboundChannel;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        ctx.writeAndFlush(Unpooled.EMPTY_BUFFER);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (relayChannel.isActive()) {
            relayChannel.writeAndFlush(msg).addListener((ChannelFutureListener) future -> {
                if (!future.isSuccess()) {
                    log.error("Failed to forward data: {}", future.cause().getMessage());
                    if (log.isDebugEnabled()) {
                        log.debug("Exception details", future.cause());
                    }
                    releaseConnection();
                    ctx.channel().close();
                }
            });
        } else {
            // If relay channel is inactive, release the message and close the connection
            if (msg instanceof io.netty.util.ReferenceCounted rc) {
                rc.release();
            }
            releaseConnection();
            ctx.channel().close();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (log.isDebugEnabled())
            log.debug("Relay channel inactive, remote address: {}", ctx.channel().remoteAddress());
        releaseConnection();
        // Only try to flush and close if the relayChannel is still active
        if (relayChannel.isActive()) {
            HttpProxyFrontendHandler.closeOnFlush(relayChannel);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        // Distinguish between client and remote server exceptions
        if (isRemoteServerException(cause)) {
            log.warn("Remote server exception: {}", cause.getMessage());
            // Remote server closed connection, release connection and log
            releaseConnection();
        } else {
            log.error("Exception in pooled relay handler: {}", cause.getMessage());
            if (log.isDebugEnabled()) {
                log.debug("Exception details", cause);
            }
            releaseConnection();
        }
        ctx.channel().close();
    }

    /**
     * Determine if it is a remote server exception
     * @param cause Exception
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
            Future<Void> releaseFuture = channelPool.release(outboundChannel);
            releaseFuture.addListener(future -> {
                if (future.isSuccess()) {
                    if (log.isDebugEnabled())
                        log.debug("Successfully released HTTPS connection back to pool");
                } else {
                    log.warn("Failed to release HTTPS connection back to pool: {}", future.cause().getMessage());
                }
            });
        }
    }
}