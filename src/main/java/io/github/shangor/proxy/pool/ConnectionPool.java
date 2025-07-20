package io.github.shangor.proxy.pool;

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
import java.util.concurrent.atomic.AtomicInteger;

public class ConnectionPool {
    private static final Logger log = LoggerFactory.getLogger(ConnectionPool.class);
    private static final Map<String, FixedChannelPool> pools = new ConcurrentHashMap<>();
    private static final Map<String, AtomicInteger> activeConnections = new ConcurrentHashMap<>();
    private static final Map<String, AtomicInteger> totalConnections = new ConcurrentHashMap<>();

    // 连接池配置
    private static int MAX_CONNECTIONS_PER_HOST = 200; // 增加到200
    private static int CONNECT_TIMEOUT_MS = 5000;
    private static int IDLE_TIMEOUT_SECONDS = 300; // 增加到5分钟
    private static double POOL_EXHAUSTION_THRESHOLD = 0.9; // 90%使用率阈值

    public static FixedChannelPool getPool(EventLoopGroup group, String host, int port, int connectTimeoutMs) {
        String key = host + ":" + port;
        return pools.computeIfAbsent(key, k -> {
            // 初始化计数器
            activeConnections.putIfAbsent(key, new AtomicInteger(0));
            totalConnections.putIfAbsent(key, new AtomicInteger(0));

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
                    totalConnections.get(key).incrementAndGet();
                    if (log.isDebugEnabled())
                        log.debug("Created new connection to {}:{}, channel: {}, total: {}",
                        host, port, ch.id(), totalConnections.get(key).get());
                    // 基础pipeline设置
                    ChannelPipeline pipeline = ch.pipeline();
                    pipeline.addLast("idle-handler", new IdleStateHandler(0, 0, IDLE_TIMEOUT_SECONDS, TimeUnit.SECONDS));
                    pipeline.addLast("http-codec", new HttpClientCodec());
                    pipeline.addLast("http-aggregator", new HttpObjectAggregator(65536));
                }

                @Override
                public void channelAcquired(Channel ch) {
                    activeConnections.get(key).incrementAndGet();
                    if (log.isDebugEnabled())
                        log.debug("Acquired connection to {}:{}, channel: {}, active: {}/{}",
                        host, port, ch.id(), activeConnections.get(key).get(), totalConnections.get(key).get());
                }

                @Override
                public void channelReleased(Channel ch) {
                    activeConnections.get(key).decrementAndGet();
                    if (log.isDebugEnabled())
                        log.debug("Released connection to {}:{}, channel: {}, active: {}/{}",
                        host, port, ch.id(), activeConnections.get(key).get(), totalConnections.get(key).get());
                    // 清理可能添加的业务处理器，保留基础的HTTP处理器
                    ChannelPipeline pipeline = ch.pipeline();

                    // 移除可能存在的业务处理器
                    String[] handlersToRemove = {"backend-handler", "response-handler", "request-handler", "proxy-connect-handler"};
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

    // 检查连接池是否接近耗尽
    public static boolean isPoolExhausted(String host, int port) {
        String key = host + ":" + port;
        AtomicInteger active = activeConnections.get(key);
        if (active == null) {
            return false;
        }
        double usage = (double) active.get() / MAX_CONNECTIONS_PER_HOST;
        return usage >= POOL_EXHAUSTION_THRESHOLD;
    }

    // 检查所有连接池是否有任何一个接近耗尽
    public static boolean isAnyPoolExhausted() {
        return activeConnections.entrySet().stream()
            .anyMatch(entry -> {
                double usage = (double) entry.getValue().get() / MAX_CONNECTIONS_PER_HOST;
                return usage >= POOL_EXHAUSTION_THRESHOLD;
            });
    }

    // 获取连接池统计信息
    public static String getPoolStats(String host, int port) {
        String key = host + ":" + port;
        AtomicInteger active = activeConnections.get(key);
        AtomicInteger total = totalConnections.get(key);
        if (active == null || total == null) {
            return "Pool not found";
        }
        return String.format("Active: %d, Total: %d, Max: %d, Usage: %.1f%%",
            active.get(), total.get(), MAX_CONNECTIONS_PER_HOST,
            (double) active.get() / MAX_CONNECTIONS_PER_HOST * 100);
    }

    // 清理指定主机的连接池
    public static void closePool(String host, int port) {
        String key = host + ":" + port;
        FixedChannelPool pool = pools.remove(key);
        if (pool != null) {
            pool.close();
            activeConnections.remove(key);
            totalConnections.remove(key);
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
        activeConnections.clear();
        totalConnections.clear();
    }

    // 增加连接池状态监控
    public static void logPoolStats() {
        if (pools.isEmpty()) {
            log.info("No active connection pools");
            return;
        }

        log.info("=== Connection Pool Statistics ===");
        pools.keySet().forEach(key -> {
            AtomicInteger active = activeConnections.get(key);
            AtomicInteger total = totalConnections.get(key);
            if (active != null && total != null) {
                double usage = (double) active.get() / MAX_CONNECTIONS_PER_HOST * 100;
                log.info("Pool {}: Active={}, Total={}, Max={}, Usage={}%",
                    key, active.get(), total.get(), MAX_CONNECTIONS_PER_HOST, String.format("%.1f", usage));
            }
        });
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

    public static void setPoolExhaustionThreshold(double threshold) {
        POOL_EXHAUSTION_THRESHOLD = threshold;
    }

    public static int getMaxConnectionsPerHost() {
        return MAX_CONNECTIONS_PER_HOST;
    }
}
