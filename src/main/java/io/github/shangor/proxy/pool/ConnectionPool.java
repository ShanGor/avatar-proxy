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

    // Connection pool configuration - performance optimization
    private static int MAX_CONNECTIONS_PER_HOST = 100; // Increase number of connections
    private static int CONNECT_TIMEOUT_MS = 3000; // Reduce connection timeout
    private static int IDLE_TIMEOUT_SECONDS = 30; // Reduce idle timeout
    private static int MAX_CONNECTION_LIFE_TIME_SECONDS = 0; // Maximum connection lifetime (seconds), 0 means no limit
    private static double POOL_EXHAUSTION_THRESHOLD = 0.8; // Lower threshold, trigger backpressure earlier

    public static FixedChannelPool getPool(EventLoopGroup group, String host, int port, int connectTimeoutMs) {
        // Include EventLoopGroup's hashCode in the key to ensure different EventLoopGroups use different connection pools
        String key = host + ":" + port + ":" + System.identityHashCode(group);
        return pools.computeIfAbsent(key, k -> {
            // Initialize counters with the new key
            activeConnections.putIfAbsent(k, new AtomicInteger(0));
            totalConnections.putIfAbsent(k, new AtomicInteger(0));

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
                    totalConnections.get(k).incrementAndGet();
                    if (log.isDebugEnabled())
                        log.debug("Created new connection to {}:{}, channel: {}, total: {}",
                        host, port, ch.id(), totalConnections.get(k).get());
                    // Basic pipeline setup
                    ChannelPipeline pipeline = ch.pipeline();
                    pipeline.addLast("idle-handler", new IdleStateHandler(0, 0, IDLE_TIMEOUT_SECONDS, TimeUnit.SECONDS));
                    pipeline.addLast("http-codec", new HttpClientCodec());
                    pipeline.addLast("http-aggregator", new HttpObjectAggregator(65536));

                    // If maximum connection lifetime is set, add a scheduled task to close the connection
                    if (MAX_CONNECTION_LIFE_TIME_SECONDS > 0) {
                        ch.eventLoop().schedule(() -> {
                            if (ch.isActive()) {
                                if (log.isDebugEnabled()) {
                                    log.debug("Closing connection {} to {}:{} due to max life time reached",
                                        ch.id(), host, port);
                                }
                                ch.close();
                            }
                        }, MAX_CONNECTION_LIFE_TIME_SECONDS, TimeUnit.SECONDS);
                    }
                }

                @Override
                public void channelAcquired(Channel ch) {
                    activeConnections.get(k).incrementAndGet();
                    if (log.isDebugEnabled())
                        log.debug("Acquired connection to {}:{}, channel: {}, active: {}/{}",
                        host, port, ch.id(), activeConnections.get(k).get(), totalConnections.get(k).get());
                }

                @Override
                public void channelReleased(Channel ch) {
                    activeConnections.get(k).decrementAndGet();
                    if (log.isDebugEnabled())
                        log.debug("Released connection to {}:{}, channel: {}, active: {}/{}",
                        host, port, ch.id(), activeConnections.get(k).get(), totalConnections.get(k).get());
                    // Clean up possible business handlers, keep basic HTTP handlers
                    ChannelPipeline pipeline = ch.pipeline();

                    // Remove possible business handlers
                    String[] handlersToRemove = {"backend-handler", "response-handler", "request-handler", "proxy-connect-handler"};
                    for (String handlerName : handlersToRemove) {
                        if (pipeline.get(handlerName) != null) {
                            pipeline.remove(handlerName);
                        }
                    }

                    // Ensure basic handlers exist
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

    // Overloaded method, use default timeout
    public static FixedChannelPool getPool(EventLoopGroup group, String host, int port) {
        return getPool(group, host, port, CONNECT_TIMEOUT_MS);
    }

    // Check if connection pool is nearly exhausted
    public static boolean isPoolExhausted(String host, int port) {
        // Need to iterate through all connection pools matching host:port
        String prefix = host + ":" + port + ":";
        return activeConnections.entrySet().stream()
            .filter(entry -> entry.getKey().startsWith(prefix))
            .anyMatch(entry -> {
                double usage = (double) entry.getValue().get() / MAX_CONNECTIONS_PER_HOST;
                return usage >= POOL_EXHAUSTION_THRESHOLD;
            });
    }

    // Get connection pool statistics
    public static String getPoolStats(String host, int port) {
        String prefix = host + ":" + port + ":";
        int totalActive = 0;
        int totalConns = 0;
        int poolCount = 0;

        for (Map.Entry<String, AtomicInteger> entry : activeConnections.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                totalActive += entry.getValue().get();
                poolCount++;
            }
        }

        for (Map.Entry<String, AtomicInteger> entry : totalConnections.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                totalConns += entry.getValue().get();
            }
        }

        if (poolCount == 0) {
            return "Pool not found";
        }

        return String.format("Pools: %d, Active: %d, Total: %d, Max per pool: %d, Usage: %.1f%%",
            poolCount, totalActive, totalConns, MAX_CONNECTIONS_PER_HOST,
            (double) totalActive / (poolCount * MAX_CONNECTIONS_PER_HOST) * 100);
    }

    // Check if any connection pool is nearly exhausted
    public static boolean isAnyPoolExhausted() {
        return activeConnections.entrySet().stream()
            .anyMatch(entry -> {
                double usage = (double) entry.getValue().get() / MAX_CONNECTIONS_PER_HOST;
                return usage >= POOL_EXHAUSTION_THRESHOLD;
            });
    }

    // Clean up connection pool for specified host
    public static void closePool(String host, int port) {
        String prefix = host + ":" + port + ":";
        pools.entrySet().removeIf(entry -> {
            if (entry.getKey().startsWith(prefix)) {
                entry.getValue().close();
                activeConnections.remove(entry.getKey());
                totalConnections.remove(entry.getKey());
                log.info("Closed connection pool for {}", entry.getKey());
                return true;
            }
            return false;
        });
    }

    // Clean up all connection pools
    public static void closeAllPools() {
        pools.forEach((key, pool) -> {
            pool.close();
            log.info("Closed connection pool for {}", key);
        });
        pools.clear();
        activeConnections.clear();
        totalConnections.clear();
    }

    // Add connection pool status monitoring
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

    public static void setMaxConnectionLifeTimeSeconds(int maxConnectionLifeTimeSeconds) {
        MAX_CONNECTION_LIFE_TIME_SECONDS = maxConnectionLifeTimeSeconds;
    }

    public static int getMaxConnectionsPerHost() {
        return MAX_CONNECTIONS_PER_HOST;
    }

    public static int getMaxConnectionLifeTimeSeconds() {
        return MAX_CONNECTION_LIFE_TIME_SECONDS;
    }
}