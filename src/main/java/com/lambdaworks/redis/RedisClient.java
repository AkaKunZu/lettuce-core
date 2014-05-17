// Copyright (C) 2011 - Will Glozer.  All rights reserved.

package com.lambdaworks.redis;

import static com.google.common.base.Preconditions.checkState;

import java.lang.reflect.Proxy;
import java.net.ConnectException;
import java.net.SocketAddress;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.google.common.collect.ImmutableList;
import com.lambdaworks.redis.codec.RedisCodec;
import com.lambdaworks.redis.codec.Utf8StringCodec;
import com.lambdaworks.redis.protocol.ChannelListener;
import com.lambdaworks.redis.protocol.Command;
import com.lambdaworks.redis.protocol.CommandHandler;
import com.lambdaworks.redis.protocol.ConnectionWatchdog;
import com.lambdaworks.redis.pubsub.PubSubCommandHandler;
import com.lambdaworks.redis.pubsub.RedisPubSubConnection;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.ChannelGroupFuture;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.HashedWheelTimer;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.internal.ConcurrentSet;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

/**
 * A scalable thread-safe <a href="http://redis.io/">Redis</a> client. Multiple threads may share one connection provided they
 * avoid blocking and transactional operations such as BLPOP and MULTI/EXEC.
 * 
 * @author Will Glozer
 */
public class RedisClient {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(RedisClient.class);

    private EventLoopGroup group;
    private Bootstrap redisBootstrap;
    private Bootstrap sentinelBootstrap;
    private HashedWheelTimer timer;
    private ChannelGroup channels;
    private long timeout;
    private TimeUnit unit;
    private RedisCodec<?, ?> codec = new Utf8StringCodec();
    private RedisURI redisURI;
    private ConnectionEvents connectionEvents = new ConnectionEvents();
    private Set<AutoCloseable> closeableResources = new ConcurrentSet<AutoCloseable>();

    /**
     * Create a new client that connects to the supplied host on the default port.
     * 
     * @param host Server hostname.
     */
    public RedisClient(String host) {
        this(host, 6379);
    }

    /**
     * Create a new client that connects to the supplied host and port. Connection attempts and non-blocking commands will
     * {@link #setDefaultTimeout timeout} after 60 seconds.
     * 
     * @param host Server hostname.
     * @param port Server port.
     */
    public RedisClient(String host, int port) {

        this(RedisURI.Builder.redis(host, port).build());

    }

    /**
     * Create a new client that connects to the supplied host and port. Connection attempts and non-blocking commands will
     * {@link #setDefaultTimeout timeout} after 60 seconds.
     * 
     * @param redisURI Redis URI.
     */
    public RedisClient(RedisURI redisURI) {
        this.redisURI = redisURI;
        group = new NioEventLoopGroup();

        redisBootstrap = new Bootstrap().channel(NioSocketChannel.class).group(group);
        sentinelBootstrap = new Bootstrap().channel(NioSocketChannel.class).group(group);

        setDefaultTimeout(redisURI.getTimeout(), redisURI.getUnit());

        channels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
        timer = new HashedWheelTimer();
        timer.start();
    }

    /**
     * Set the default timeout for {@link RedisConnection connections} created by this client. The timeout applies to connection
     * attempts and non-blocking commands.
     * 
     * @param timeout Default connection timeout.
     * @param unit Unit of time for the timeout.
     */
    public void setDefaultTimeout(long timeout, TimeUnit unit) {
        this.timeout = timeout;
        this.unit = unit;
        redisBootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) unit.toMillis(timeout));
    }

    /**
     * Open a new synchronous connection to the redis server that treats keys and values as UTF-8 strings.
     * 
     * @return A new connection.
     */
    public <T extends BaseRedisConnection<String, String>> T connect() {
        return (T) connect((RedisCodec) codec);
    }

    public <T extends BaseRedisConnection<String, String>> RedisConnectionPool<T> pool() {
        return pool(5, 20);
    }

    public <T extends BaseRedisConnection<String, String>> RedisConnectionPool<T> pool(int maxIdle, int maxActive) {

        long maxWait = unit.convert(timeout, TimeUnit.MILLISECONDS);
        RedisConnectionPool<RedisConnection<String, String>> pool = new RedisConnectionPool<RedisConnection<String, String>>(
                new RedisConnectionProvider<RedisConnection<String, String>>() {
                    @Override
                    public RedisConnection<String, String> createConnection() {
                        return connect(codec, false);
                    }

                    @Override
                    public Class<RedisConnection<String, String>> getComponentType() {
                        return (Class) RedisConnection.class;
                    }
                }, maxActive, maxIdle, maxWait);

        pool.addListener(new CloseEvents.CloseListener() {
            @Override
            public void resourceClosed(Object resource) {
                closeableResources.remove(resource);
            }
        });

        closeableResources.add(pool);

        return (RedisConnectionPool<T>) pool;
    }

    public <T extends BaseRedisAsyncConnection<String, String>> RedisConnectionPool<T> asyncPool() {
        return asyncPool(5, 20);
    }

    public <T extends BaseRedisAsyncConnection<String, String>> RedisConnectionPool<T> asyncPool(int maxIdle, int maxActive) {

        long maxWait = unit.convert(timeout, TimeUnit.MILLISECONDS);
        RedisConnectionPool<RedisAsyncConnection<String, String>> pool = new RedisConnectionPool<RedisAsyncConnection<String, String>>(
                new RedisConnectionProvider<RedisAsyncConnection<String, String>>() {
                    @Override
                    public RedisAsyncConnection<String, String> createConnection() {
                        return (RedisAsyncConnection<String, String>) connectAsyncImpl(codec, false);
                    }

                    @Override
                    public Class<RedisAsyncConnection<String, String>> getComponentType() {
                        return (Class) RedisAsyncConnection.class;
                    }
                }, maxActive, maxIdle, maxWait);

        pool.addListener(new CloseEvents.CloseListener() {
            @Override
            public void resourceClosed(Object resource) {
                closeableResources.remove(resource);
            }
        });

        closeableResources.add(pool);

        return (RedisConnectionPool<T>) pool;
    }

    /**
     * Open a new asynchronous connection to the redis server that treats keys and values as UTF-8 strings.
     * 
     * @return A new connection.
     */
    public <T extends BaseRedisAsyncConnection<String, String>> T connectAsync() {
        return (T) connectAsync((RedisCodec) codec);
    }

    /**
     * Open a new pub/sub connection to the redis server that treats keys and values as UTF-8 strings.
     * 
     * @return A new connection.
     */
    public RedisPubSubConnection<String, String> connectPubSub() {
        return connectPubSub((RedisCodec) codec);
    }

    /**
     * Open a new synchronous connection to the redis server. Use the supplied {@link RedisCodec codec} to encode/decode keys
     * and values.
     * 
     * @param codec Use this codec to encode/decode keys and values.
     * 
     * @return A new connection.
     */
    public <K, V, T extends BaseRedisConnection<K, V>> T connect(RedisCodec<K, V> codec) {

        RedisConnection<K, V> c = connect(codec, true);
        return (T) c;
    }

    private <K, V> RedisConnection connect(RedisCodec<K, V> codec, boolean withReconnect) {
        FutureSyncInvocationHandler h = new FutureSyncInvocationHandler(connectAsyncImpl(codec, withReconnect));
        return (RedisConnection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[] { RedisConnection.class }, h);
    }

    /**
     * Open a new asynchronous connection to the redis server. Use the supplied {@link RedisCodec codec} to encode/decode keys
     * and values.
     * 
     * @param codec Use this codec to encode/decode keys and values.
     * 
     * @return A new connection.
     */
    public <K, V, T extends BaseRedisAsyncConnection<K, V>> T connectAsync(RedisCodec<K, V> codec) {
        return (T) connectAsyncImpl(codec, true);
    }

    private <K, V> RedisAsyncConnectionImpl<K, V> connectAsyncImpl(RedisCodec<K, V> codec, boolean withReconnect) {
        BlockingQueue<Command<K, V, ?>> queue = new LinkedBlockingQueue<Command<K, V, ?>>();

        CommandHandler<K, V> handler = new CommandHandler<K, V>(queue);
        RedisAsyncConnectionImpl<K, V> connection = new RedisAsyncConnectionImpl<K, V>(queue, codec, timeout, unit);

        return connectAsyncImpl(handler, connection, withReconnect);
    }

    /**
     * Open a new pub/sub connection to the redis server. Use the supplied {@link RedisCodec codec} to encode/decode keys and
     * values.
     * 
     * @param codec Use this codec to encode/decode keys and values.
     * 
     * @return A new pub/sub connection.
     */
    public <K, V> RedisPubSubConnection<K, V> connectPubSub(RedisCodec<K, V> codec) {
        BlockingQueue<Command<K, V, ?>> queue = new LinkedBlockingQueue<Command<K, V, ?>>();

        PubSubCommandHandler<K, V> handler = new PubSubCommandHandler<K, V>(queue, codec);
        RedisPubSubConnection<K, V> connection = new RedisPubSubConnection<K, V>(queue, codec, timeout, unit);

        return connectAsyncImpl(handler, connection, true);
    }

    private <K, V, T extends RedisAsyncConnectionImpl<K, V>> T connectAsyncImpl(final CommandHandler<K, V> handler,
            final T connection, final boolean withReconnect) {
        try {

            SocketAddress redisAddress;

            if (redisURI.getSentinelMasterId() != null && !redisURI.getSentinels().isEmpty()) {
                logger.debug("Connecting to Redis using Sentinels " + redisURI.getSentinels() + ", MasterId "
                        + redisURI.getSentinelMasterId());
                redisAddress = lookupRedis(redisURI.getSentinelMasterId());

            } else {
                redisAddress = redisURI.getResolvedAddress();
            }

            logger.debug("Connecting to Redis, address: " + redisAddress);

            redisBootstrap.handler(new ChannelInitializer<Channel>() {
                @Override
                protected void initChannel(Channel ch) throws Exception {

                    if (withReconnect) {
                        ConnectionWatchdog watchdog = new ConnectionWatchdog(redisBootstrap, timer);
                        ch.pipeline().addLast(watchdog);
                        watchdog.setReconnect(true);
                    }

                    ch.pipeline().addLast(new ChannelListener(channels),
                            new ConnectionEventTrigger(connectionEvents, connection), handler, connection);
                }
            });

            redisBootstrap.connect(redisAddress).sync();

            connection.addListener(new CloseEvents.CloseListener() {
                @Override
                public void resourceClosed(Object resource) {
                    closeableResources.remove(resource);
                }
            });
            closeableResources.add(connection);

            if (redisURI.getPassword() != null) {
                connection.auth(redisURI.getPassword());
            }

            if (redisURI.getDatabase() != 0) {
                connection.select(redisURI.getDatabase());
            }

            return connection;
        } catch (Throwable e) {
            throw new RedisException("Unable to connect", e);
        }
    }

    private SocketAddress lookupRedis(String sentinelMasterId) throws InterruptedException, TimeoutException,
            ExecutionException {
        RedisSentinelConnectionImpl connection = connectSentinelAsync();
        try {
            return (SocketAddress) connection.getMasterAddrByName(sentinelMasterId).get(timeout, unit);
        } finally {
            connection.close();
        }
    }

    public <K, V> RedisSentinelConnectionImpl connectSentinelAsync() throws InterruptedException {

        checkState(!redisURI.getSentinels().isEmpty(), "cannot connect Redis Sentinel, redisSentinelAddress is not set");

        BlockingQueue<Command<K, V, ?>> queue = new LinkedBlockingQueue<Command<K, V, ?>>();

        final CommandHandler commandHandler = new CommandHandler(queue);
        final RedisSentinelConnectionImpl connection = new RedisSentinelConnectionImpl(codec, queue, timeout, unit);

        logger.debug("Trying to get a Sentinel connection for one of: " + redisURI.getSentinels());

        sentinelBootstrap.handler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) throws Exception {

                ConnectionWatchdog watchdog = new ConnectionWatchdog(sentinelBootstrap, timer);
                ch.pipeline().addLast(watchdog);
                watchdog.setReconnect(true);

                ch.pipeline().addLast(new ChannelListener(channels), watchdog, commandHandler, connection,
                        new ConnectionEventTrigger(connectionEvents, connection));
            }
        });

        boolean connected = false;
        Exception causingException = null;
        for (RedisURI uri : redisURI.getSentinels()) {

            sentinelBootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) uri.getUnit().toMillis(uri.getTimeout()));
            ChannelFuture connect = sentinelBootstrap.connect(uri.getResolvedAddress());
            logger.debug("Connecting to Sentinel, address: " + uri.getResolvedAddress());
            try {
                connect.sync();
                connected = true;
            } catch (Exception e) {
                logger.warn("Cannot connect sentinel at " + uri.getHost() + ":" + uri.getPort() + ": " + e.toString());
                if (causingException == null) {
                    causingException = e;
                } else {
                    causingException.addSuppressed(e);
                }
                if (e instanceof ConnectException) {
                    continue;
                }
            }
        }

        if (!connected) {
            throw new RedisException("Cannot connect to a sentinel: " + redisURI.getSentinels(), causingException);
        }

        connection.addListener(new CloseEvents.CloseListener() {
            @Override
            public void resourceClosed(Object resource) {
                closeableResources.remove(resource);
            }
        });

        return connection;
    }

    /**
     * Shutdown this client and close all open connections. The client should be discarded after calling shutdown.
     */
    public void shutdown() {

        ImmutableList<AutoCloseable> autoCloseables = ImmutableList.copyOf(closeableResources);
        for (AutoCloseable closeableResource : autoCloseables) {
            try {
                closeableResource.close();
            } catch (Exception e) {
                logger.debug("Exception on Close: " + e.getMessage(), e);

            }
        }

        for (Channel c : channels) {
            ChannelPipeline pipeline = c.pipeline();

            RedisAsyncConnectionImpl<?, ?> asyncConnection = pipeline.get(RedisAsyncConnectionImpl.class);
            if (asyncConnection != null && !asyncConnection.isClosed()) {
                asyncConnection.close();
            }

            RedisSentinelConnectionImpl<?, ?> sentinelConnection = pipeline.get(RedisSentinelConnectionImpl.class);
            if (sentinelConnection != null && !sentinelConnection.isClosed()) {
                sentinelConnection.close();
            }
        }
        ChannelGroupFuture future = channels.close();
        future.awaitUninterruptibly();
        group.shutdownGracefully().syncUninterruptibly();
        timer.stop();
    }

    protected int getResourceCount() {
        return closeableResources.size();
    }

    protected int getChannelCount() {
        return channels.size();
    }

    public void addListener(RedisConnectionStateListener listener) {
        connectionEvents.addListener(listener);
    }

    public void removeListener(RedisConnectionStateListener listener) {
        connectionEvents.removeListener(listener);
    }
}
