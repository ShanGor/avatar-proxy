package io.github.shangor.proxy.util;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.pool.ChannelPoolHandler;
import io.netty.channel.pool.FixedChannelPool;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class ConnectionPool {
    private static final Logger log = LoggerFactory.getLogger(ConnectionPool.class);
    private static final Map<String, FixedChannelPool> pools = new ConcurrentHashMap<>();

    // 连接池配置
    private static int MAX_CONNECTIONS_PER_HOST = 100; // 每个主机最大连接数
    private static int CONNECT_TIMEOUT_MS = 5000;
    private static int IDLE_TIMEOUT_SECONDS = 60;

    public static FixedChannelPool getPool(EventLoopGroup group, String host, int port, int connectTimeoutMs) {
        String key = host + ":" + port;
        return pools.computeIfAbsent(key, k -> {
            Bootstrap bootstrap = new Bootstrap()
                    .group(group)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
                    .remoteAddress(new InetSocketAddress(host, port));

            return new FixedChannelPool(bootstrap, new ChannelPoolHandler() {
                @Override
                public void channelCreated(Channel ch) {
                    log.debug("Created new connection to {}:{}, channel: {}", host, port, ch.id());
                    // 基础pipeline设置
                    ChannelPipeline pipeline = ch.pipeline();
                    pipeline.addLast("idle-handler", new IdleStateHandler(0, 0, IDLE_TIMEOUT_SECONDS, TimeUnit.SECONDS));
                    pipeline.addLast("http-codec", new HttpClientCodec());
                    pipeline.addLast("http-aggregator", new HttpObjectAggregator(65536));
                }

                @Override
                public void channelAcquired(Channel ch) {
                    log.debug("Acquired connection to {}:{}, channel: {}", host, port, ch.id());
                }

                @Override
                public void channelReleased(Channel ch) {
                    log.debug("Released connection to {}:{}, channel: {}", host, port, ch.id());
                    // 清理可能添加的业务处理器，保留基础的HTTP处理器
                    ChannelPipeline pipeline = ch.pipeline();

                    // 移除可能存在的业务处理器
                    String[] handlersToRemove = {"backend-handler", "response-handler", "request-handler"};
                    for (String handlerName : handlersToRemove) {
                        if (pipeline.get(handlerName) != null) {
                            pipeline.remove(handlerName);
                        }
                    }

                    // 确保基础处理器存在
                    if (pipeline.get("http-codec") == null) {
                        pipeline.addLast("http-codec", new HttpClientCodec());
                    }
                    if (pipeline.get("http-aggregator") == null) {
                        pipeline.addLast("http-aggregator", new HttpObjectAggregator(65536));
                    }
                }
            }, MAX_CONNECTIONS_PER_HOST);
        });
    }

    // 重载方法，使用默认超时时间
    public static FixedChannelPool getPool(EventLoopGroup group, String host, int port) {
        return getPool(group, host, port, CONNECT_TIMEOUT_MS);
    }

    // 清理指定主机的连接池
    public static void closePool(String host, int port) {
        String key = host + ":" + port;
        FixedChannelPool pool = pools.remove(key);
        if (pool != null) {
            pool.close();
            log.info("Closed connection pool for {}:{}", host, port);
        }
    }

    // 清理所有连接池
    public static void closeAllPools() {
        pools.forEach((key, pool) -> {
            pool.close();
            log.info("Closed connection pool for {}", key);
        });
        pools.clear();
    }

    public static void setMaxConnectionsPerHost(int maxConnectionsPerHost) {
        MAX_CONNECTIONS_PER_HOST = maxConnectionsPerHost;
    }

    public static void setConnectTimeoutMs(int connectTimeoutMs) {
        CONNECT_TIMEOUT_MS = connectTimeoutMs;
    }

    public static void setIdleTimeoutSeconds(int idleTimeoutSeconds) {
        IDLE_TIMEOUT_SECONDS = idleTimeoutSeconds;
    }
}
