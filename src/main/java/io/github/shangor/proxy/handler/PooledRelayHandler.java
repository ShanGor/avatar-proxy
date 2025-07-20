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
                    log.error("Failed to forward data", future.cause());
                    releaseConnection();
                    ctx.channel().close();
                }
            });
        } else {
            // 如果relay channel不活跃，释放消息并关闭连接
            if (msg instanceof io.netty.util.ReferenceCounted) {
                ((io.netty.util.ReferenceCounted) msg).release();
            }
            releaseConnection();
            ctx.channel().close();
        }
    }
    
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        releaseConnection();
        if (relayChannel.isActive()) {
            HttpProxyFrontendHandler.closeOnFlush(relayChannel);
        }
    }
    
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Exception in pooled relay handler", cause);
        releaseConnection();
        ctx.channel().close();
    }
    
    private void releaseConnection() {
        if (!connectionReleased && channelPool != null && outboundChannel != null) {
            connectionReleased = true;
            Future<Void> releaseFuture = channelPool.release(outboundChannel);
            releaseFuture.addListener(future -> {
                if (future.isSuccess()) {
                    log.debug("Successfully released HTTPS connection back to pool");
                } else {
                    log.warn("Failed to release HTTPS connection back to pool: {}", future.cause().getMessage());
                }
            });
        }
    }
}