package io.github.shangor.proxy.handler;

import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RelayHandler extends ChannelInboundHandlerAdapter {
    private static final Logger log = LoggerFactory.getLogger(RelayHandler.class);
    
    private final Channel relayChannel;
    
    public RelayHandler(Channel relayChannel) {
        this.relayChannel = relayChannel;
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
                    log.error("转发数据失败", future.cause());
                    ctx.channel().close();
                }
            });
        } else {
            ctx.close();
        }
    }
    
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (relayChannel.isActive()) {
            HttpProxyFrontendHandler.closeOnFlush(relayChannel);
        }
    }
    
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("RelayHandler异常", cause);
        ctx.close();
    }
}