package io.github.shangor.proxy.handler;

import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.FullHttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpProxyBackendHandler extends SimpleChannelInboundHandler<FullHttpResponse> {
    private static final Logger logger = LoggerFactory.getLogger(HttpProxyBackendHandler.class);
    
    private final Channel inboundChannel;
    
    public HttpProxyBackendHandler(Channel inboundChannel) {
        this.inboundChannel = inboundChannel;
    }
    
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse response) {
        response.retain();
        inboundChannel.writeAndFlush(response).addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess()) {
                logger.error("向客户端发送响应失败", future.cause());
                HttpProxyFrontendHandler.closeOnFlush(ctx.channel());
            }
        });
    }
    
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("后端处理器异常", cause);
        HttpProxyFrontendHandler.closeOnFlush(ctx.channel());
    }
    
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        HttpProxyFrontendHandler.closeOnFlush(inboundChannel);
    }
}