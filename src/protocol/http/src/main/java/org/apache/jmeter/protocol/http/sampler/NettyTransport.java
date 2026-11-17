/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.jmeter.protocol.http.sampler;

import java.net.ConnectException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLException;

import org.apache.jmeter.util.JMeterUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.pool.AbstractChannelPoolMap;
import io.netty.channel.pool.ChannelPoolHandler;
import io.netty.channel.pool.FixedChannelPool;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

/**
 * Shared Netty transport behind the {@code Netty} HTTP sampler implementation.
 *
 * <p>The transport is deliberately dumb and shared: a single event loop group
 * and one channel pool per target serve every virtual user. It knows nothing
 * about JMeter semantics — cookies, variables, authentication and redirects
 * stay on the calling (virtual) thread in {@link HTTPNettyImpl}, which parks
 * on the {@link CompletableFuture} returned here.</p>
 *
 * <p>The number of event loops defaults to the number of available processors
 * and can be set with the {@code pmeasure.netty.eventloops} property; whether
 * one loop per core or two is faster at scale is settled by benchmark, not by
 * assumption.</p>
 *
 * @since 1.0.0 (PMeasure)
 */
final class NettyTransport {

    private static final Logger log = LoggerFactory.getLogger(NettyTransport.class);

    static final String EVENT_LOOPS_PROPERTY = "pmeasure.netty.eventloops"; // $NON-NLS-1$

    private static final AttributeKey<CompletableFuture<NettyResponse>> PENDING =
            AttributeKey.valueOf("pmeasure.pendingResponse"); // $NON-NLS-1$

    /** Upper bound for one aggregated response held in memory. */
    private static final int MAX_CONTENT_LENGTH = 100 * 1024 * 1024;

    private static volatile NettyTransport instance;

    private final EventLoopGroup group;
    private final SslContext sslContext;
    private final AbstractChannelPoolMap<PoolKey, FixedChannelPool> pools;

    private NettyTransport() throws SSLException {
        int eventLoops = JMeterUtils.getPropDefault(EVENT_LOOPS_PROPERTY, 0);
        if (eventLoops <= 0) {
            eventLoops = Runtime.getRuntime().availableProcessors();
        }
        this.group = new NioEventLoopGroup(eventLoops, runnable -> {
            Thread thread = new Thread(runnable, "pmeasure-netty-eventloop"); // $NON-NLS-1$
            thread.setDaemon(true);
            return thread;
        });
        // Matches the default behaviour of JMeter's other implementations,
        // which do not verify the certificate of the server either.
        this.sslContext = SslContextBuilder.forClient()
                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                .build();
        this.pools = new AbstractChannelPoolMap<>() {
            @Override
            protected FixedChannelPool newPool(PoolKey key) {
                Bootstrap bootstrap = new Bootstrap();
                bootstrap.group(group)
                        .channel(NioSocketChannel.class)
                        .option(ChannelOption.TCP_NODELAY, true)
                        .remoteAddress(key.host(), key.port());
                if (key.connectTimeoutMillis() > 0) {
                    bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, key.connectTimeoutMillis());
                }
                return new FixedChannelPool(bootstrap, new PoolHandler(key), Integer.MAX_VALUE);
            }
        };
    }

    static NettyTransport get() {
        NettyTransport local = instance;
        if (local == null) {
            synchronized (NettyTransport.class) {
                local = instance;
                if (local == null) {
                    try {
                        local = new NettyTransport();
                    } catch (SSLException e) {
                        throw new IllegalStateException("Could not create the Netty SSL context", e);
                    }
                    instance = local;
                    Runtime.getRuntime().addShutdownHook(
                            new Thread(local::shutdown, "pmeasure-netty-shutdown")); // $NON-NLS-1$
                }
            }
        }
        return local;
    }

    /**
     * Sends one HTTP/1.1 request and completes the returned future with the
     * aggregated response. Failures complete the future exceptionally with the
     * underlying cause (a connect exception, a read timeout, a TLS failure),
     * so the sampler can map them the way the other implementations do.
     *
     * @param request the request to send; ownership transfers to the transport
     * @param secure whether the target expects TLS
     * @param host target host name
     * @param port target port
     * @param connectTimeoutMillis connect timeout in milliseconds, 0 for none
     * @param readTimeoutMillis read timeout in milliseconds, 0 for none
     * @return future of the response
     */
    CompletableFuture<NettyResponse> execute(FullHttpRequest request, boolean secure, String host, int port,
            int connectTimeoutMillis, int readTimeoutMillis) {
        PoolKey key = new PoolKey(secure, host, port, connectTimeoutMillis, readTimeoutMillis);
        FixedChannelPool pool = pools.get(key);
        CompletableFuture<NettyResponse> result = new CompletableFuture<>();
        Future<Channel> acquire = pool.acquire();
        acquire.addListener((GenericFutureListener<Future<Channel>>) future -> {
            if (!future.isSuccess()) {
                request.release();
                result.completeExceptionally(future.cause());
                return;
            }
            Channel channel = future.getNow();
            channel.attr(PENDING).set(result);
            ChannelFuture write = channel.writeAndFlush(request);
            write.addListener(wf -> {
                if (!wf.isSuccess()) {
                    result.completeExceptionally(wf.cause());
                }
            });
            result.whenComplete((response, error) -> {
                if (error != null || response == null || !response.keepAlive() || !channel.isActive()) {
                    channel.close();
                }
                pool.release(channel);
            });
        });
        return result;
    }

    void shutdown() {
        try {
            pools.close();
        } catch (Exception e) {
            log.debug("Error while closing the Netty channel pools", e);
        }
        group.shutdownGracefully(0, 5, TimeUnit.SECONDS);
    }

    /** Creates a request; kept here so the sampler never touches buffers directly. */
    static FullHttpRequest newRequest(String method, String uri, byte[] body) {
        ByteBuf content = body == null || body.length == 0 ? Unpooled.EMPTY_BUFFER : Unpooled.wrappedBuffer(body);
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.valueOf(method), uri, content);
        if (body != null) {
            request.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
        }
        return request;
    }

    /** Identifies one pool: one target, with the timeouts its pipeline was built with. */
    private record PoolKey(boolean secure, String host, int port,
            int connectTimeoutMillis, int readTimeoutMillis) {
    }

    /** Assembles the pipeline of every new channel of one pool. */
    private final class PoolHandler implements ChannelPoolHandler {

        private final PoolKey key;

        PoolHandler(PoolKey key) {
            this.key = key;
        }

        @Override
        public void channelCreated(Channel channel) {
            if (key.secure()) {
                channel.pipeline().addLast(sslContext.newHandler(channel.alloc(), key.host(), key.port()));
            }
            channel.pipeline().addLast(new HttpClientCodec());
            channel.pipeline().addLast(new HttpContentDecompressor());
            channel.pipeline().addLast(new HttpObjectAggregator(MAX_CONTENT_LENGTH));
            if (key.readTimeoutMillis() > 0) {
                channel.pipeline().addLast(new ReadTimeoutHandler(key.readTimeoutMillis(), TimeUnit.MILLISECONDS));
            }
            channel.pipeline().addLast(new ResponseHandler());
        }

        @Override
        public void channelAcquired(Channel channel) {
            // No per-user state is ever stored on a channel.
        }

        @Override
        public void channelReleased(Channel channel) {
            // The pipeline is stateless between requests; the pending future is
            // replaced on the next acquire.
        }
    }

    /** Completes the pending future of a channel when its response arrives. */
    private static final class ResponseHandler extends SimpleChannelInboundHandler<FullHttpResponse> {

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse msg) {
            CompletableFuture<NettyResponse> pending = ctx.channel().attr(PENDING).getAndSet(null);
            if (pending != null) {
                pending.complete(new NettyResponse(msg.retain()));
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            CompletableFuture<NettyResponse> pending = ctx.channel().attr(PENDING).getAndSet(null);
            if (pending != null) {
                pending.completeExceptionally(cause);
            }
            ctx.close();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            CompletableFuture<NettyResponse> pending = ctx.channel().attr(PENDING).getAndSet(null);
            if (pending != null) {
                pending.completeExceptionally(
                        new ConnectException("Connection closed before a response was received"));
            }
            ctx.fireChannelInactive();
        }
    }

    /** One aggregated HTTP response. */
    static final class NettyResponse {
        private final FullHttpResponse response;

        NettyResponse(FullHttpResponse response) {
            this.response = response;
        }

        FullHttpResponse response() {
            return response;
        }

        boolean keepAlive() {
            return HttpUtil.isKeepAlive(response);
        }
    }
}
