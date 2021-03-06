/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.queryablestate.network;

import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.typeutils.base.IntSerializer;
import org.apache.flink.queryablestate.client.VoidNamespace;
import org.apache.flink.queryablestate.client.VoidNamespaceSerializer;
import org.apache.flink.queryablestate.client.state.serialization.KvStateSerializer;
import org.apache.flink.queryablestate.messages.KvStateInternalRequest;
import org.apache.flink.queryablestate.messages.KvStateResponse;
import org.apache.flink.queryablestate.network.messages.MessageSerializer;
import org.apache.flink.queryablestate.network.messages.MessageType;
import org.apache.flink.queryablestate.network.stats.AtomicKvStateRequestStats;
import org.apache.flink.queryablestate.network.stats.KvStateRequestStats;
import org.apache.flink.queryablestate.server.KvStateServerImpl;
import org.apache.flink.runtime.operators.testutils.DummyEnvironment;
import org.apache.flink.runtime.query.KvStateRegistry;
import org.apache.flink.runtime.state.AbstractInternalStateBackend;
import org.apache.flink.runtime.state.AbstractStateBackend;
import org.apache.flink.runtime.state.KeyGroupRange;
import org.apache.flink.runtime.state.KeyedStateBackendWrapper;
import org.apache.flink.runtime.state.context.ContextStateHelper;
import org.apache.flink.runtime.state.heap.KeyContextImpl;
import org.apache.flink.runtime.state.memory.MemoryStateBackend;

import org.apache.flink.shaded.netty4.io.netty.bootstrap.Bootstrap;
import org.apache.flink.shaded.netty4.io.netty.buffer.ByteBuf;
import org.apache.flink.shaded.netty4.io.netty.channel.Channel;
import org.apache.flink.shaded.netty4.io.netty.channel.ChannelHandler;
import org.apache.flink.shaded.netty4.io.netty.channel.ChannelHandlerContext;
import org.apache.flink.shaded.netty4.io.netty.channel.ChannelInboundHandlerAdapter;
import org.apache.flink.shaded.netty4.io.netty.channel.ChannelInitializer;
import org.apache.flink.shaded.netty4.io.netty.channel.EventLoopGroup;
import org.apache.flink.shaded.netty4.io.netty.channel.nio.NioEventLoopGroup;
import org.apache.flink.shaded.netty4.io.netty.channel.socket.SocketChannel;
import org.apache.flink.shaded.netty4.io.netty.channel.socket.nio.NioSocketChannel;
import org.apache.flink.shaded.netty4.io.netty.handler.codec.LengthFieldBasedFrameDecoder;

import org.junit.AfterClass;
import org.junit.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link KvStateServerImpl}.
 */
public class KvStateServerTest {

	// Thread pool for client bootstrap (shared between tests)
	private static final NioEventLoopGroup NIO_GROUP = new NioEventLoopGroup();

	private static final int TIMEOUT_MILLIS = 10000;

	@AfterClass
	public static void tearDown() throws Exception {
		if (NIO_GROUP != null) {
			// note: no "quiet period" to not trigger Netty#4357
			NIO_GROUP.shutdownGracefully(0, 10, TimeUnit.SECONDS);
		}
	}

	/**
	 * Tests a simple successful query via a SocketChannel.
	 */
	@Test
	public void testSimpleRequest() throws Throwable {
		KvStateServerImpl server = null;
		Bootstrap bootstrap = null;
		try {
			KvStateRegistry registry = new KvStateRegistry();
			KvStateRequestStats stats = new AtomicKvStateRequestStats();

			server = new KvStateServerImpl(
				InetAddress.getLocalHost(),
				Collections.singletonList(0).iterator(),
				1,
				1,
				registry,
				stats);
			server.start();

			InetSocketAddress serverAddress = server.getServerAddress();
			int numKeyGroups = 1;
			KeyGroupRange groupRange = new KeyGroupRange(0, 0);
			AbstractStateBackend abstractBackend = new MemoryStateBackend();
			DummyEnvironment dummyEnv = new DummyEnvironment("test", 1, 0);
			dummyEnv.setKvStateRegistry(registry);
			AbstractInternalStateBackend internalStateBackend = abstractBackend.createInternalStateBackend(
				dummyEnv,
				"test_op",
				numKeyGroups,
				groupRange);
			KeyContextImpl<Integer> keyContext = new KeyContextImpl<>(IntSerializer.INSTANCE, numKeyGroups, groupRange);
			ContextStateHelper contextStateHelper = new ContextStateHelper(keyContext, dummyEnv.getExecutionConfig(), internalStateBackend);
			KeyedStateBackendWrapper<Integer> backend = new KeyedStateBackendWrapper<>(contextStateHelper);

			final KvStateServerHandlerTest.TestRegistryListener registryListener =
				new KvStateServerHandlerTest.TestRegistryListener();

			registry.registerListener(dummyEnv.getJobID(), registryListener);

			ValueStateDescriptor<Integer> desc = new ValueStateDescriptor<>("any", IntSerializer.INSTANCE);
			desc.setQueryable("vanilla");

			ValueState<Integer> state = backend.getPartitionedState(
				VoidNamespace.INSTANCE,
				VoidNamespaceSerializer.INSTANCE,
				desc);

			// Update KvState
			int expectedValue = 712828289;

			int key = 99812822;
			backend.setCurrentKey(key);
			state.update(expectedValue);

			// Request
			byte[] serializedKeyAndNamespace = KvStateSerializer.serializeKeyAndNamespace(
				key,
				IntSerializer.INSTANCE,
				VoidNamespace.INSTANCE,
				VoidNamespaceSerializer.INSTANCE);

			// Connect to the server
			final BlockingQueue<ByteBuf> responses = new LinkedBlockingQueue<>();
			bootstrap = createBootstrap(
				new LengthFieldBasedFrameDecoder(Integer.MAX_VALUE, 0, 4, 0, 4),
				new ChannelInboundHandlerAdapter() {
					@Override
					public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
						responses.add((ByteBuf) msg);
					}
				});

			Channel channel = bootstrap
				.connect(serverAddress.getAddress(), serverAddress.getPort())
				.sync().channel();

			long requestId = Integer.MAX_VALUE + 182828L;

			assertTrue(registryListener.registrationName.equals("vanilla"));

			final KvStateInternalRequest request = new KvStateInternalRequest(
				registryListener.kvStateId,
				serializedKeyAndNamespace);

			ByteBuf serializeRequest = MessageSerializer.serializeRequest(
				channel.alloc(),
				requestId,
				request);

			channel.writeAndFlush(serializeRequest);

			ByteBuf buf = responses.poll(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);

			assertEquals(MessageType.REQUEST_RESULT, MessageSerializer.deserializeHeader(buf));
			assertEquals(requestId, MessageSerializer.getRequestId(buf));
			KvStateResponse response = server.getSerializer().deserializeResponse(buf);

			int actualValue = KvStateSerializer.deserializeValue(response.getContent(), IntSerializer.INSTANCE);
			assertEquals(expectedValue, actualValue);
		} finally {
			if (server != null) {
				server.shutdown();
			}

			if (bootstrap != null) {
				EventLoopGroup group = bootstrap.group();
				if (group != null) {
					// note: no "quiet period" to not trigger Netty#4357
					group.shutdownGracefully(0, 10, TimeUnit.SECONDS);
				}
			}
		}
	}

	/**
	 * Creates a client bootstrap.
	 */
	private Bootstrap createBootstrap(final ChannelHandler... handlers) {
		return new Bootstrap().group(NIO_GROUP).channel(NioSocketChannel.class)
			.handler(new ChannelInitializer<SocketChannel>() {
				@Override
				protected void initChannel(SocketChannel ch) throws Exception {
					ch.pipeline().addLast(handlers);
				}
			});
	}

}
