/*
 * (C) Copyright 2015-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *   ohun@live.cn (夜色)
 */

package com.mpush.core.server;

import com.mpush.api.protocol.Command;
import com.mpush.api.service.Listener;
import com.mpush.common.MessageDispatcher;
import com.mpush.core.handler.GatewayPushHandler;
import com.mpush.netty.server.NettyTCPServer;
import com.mpush.tools.config.CC;
import com.mpush.tools.config.CC.mp.net.rcv_buf;
import com.mpush.tools.config.CC.mp.net.snd_buf;
import com.mpush.tools.thread.NamedPoolThreadFactory;
import com.mpush.tools.thread.ThreadNames;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.sctp.nio.NioSctpServerChannel;
import io.netty.channel.udt.nio.NioUdtProvider;
import io.netty.handler.traffic.GlobalChannelTrafficShapingHandler;

import java.nio.channels.spi.SelectorProvider;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static com.mpush.tools.config.CC.mp.net.traffic_shaping.gateway_server.*;
import static com.mpush.tools.config.CC.mp.net.write_buffer_water_mark.gateway_server_high;
import static com.mpush.tools.config.CC.mp.net.write_buffer_water_mark.gateway_server_low;
import static com.mpush.tools.thread.ThreadNames.T_TRAFFIC_SHAPING;

/**
 * Created by ohun on 2015/12/30.
 *
 * @author ohun@live.cn
 */
public final class GatewayServer extends NettyTCPServer {
    private static GatewayServer I;

    private ServerChannelHandler channelHandler;
    private ServerConnectionManager connectionManager;
    private GlobalChannelTrafficShapingHandler trafficShapingHandler;
    private ScheduledExecutorService trafficShapingExecutor;

    public static GatewayServer I() {
        if (I == null) {
            synchronized (GatewayServer.class) {
                if (I == null) {
                    I = new GatewayServer();
                }
            }
        }
        return I;
    }

    private GatewayServer() {
        super(CC.mp.net.gateway_server_port);
    }

    @Override
    public void init() {
        super.init();
        MessageDispatcher receiver = new MessageDispatcher();
        receiver.register(Command.GATEWAY_PUSH, new GatewayPushHandler());
        connectionManager = new ServerConnectionManager(false);
        channelHandler = new ServerChannelHandler(false, connectionManager, receiver);

        if (CC.mp.net.traffic_shaping.gateway_server.enabled) {//启用流量整形，限流
            trafficShapingExecutor = Executors.newSingleThreadScheduledExecutor(new NamedPoolThreadFactory(T_TRAFFIC_SHAPING));
            trafficShapingHandler = new GlobalChannelTrafficShapingHandler(
                    trafficShapingExecutor,
                    write_global_limit, read_global_limit,
                    write_channel_limit, read_channel_limit,
                    check_interval);
        }
    }

    @Override
    public void stop(Listener listener) {
        super.stop(listener);
        if (trafficShapingHandler != null) {
            trafficShapingHandler.release();
            trafficShapingExecutor.shutdown();
        }
        if (connectionManager != null) {
            connectionManager.destroy();
        }
    }

    @Override
    protected String getBossThreadName() {
        return ThreadNames.T_GATEWAY_BOSS;
    }

    @Override
    protected String getWorkThreadName() {
        return ThreadNames.T_GATEWAY_WORKER;
    }

    @Override
    protected int getIoRate() {
        return 100;
    }

    @Override
    protected int getWorkThreadNum() {
        return CC.mp.thread.pool.gateway_server_work;
    }

    @Override
    protected void initPipeline(ChannelPipeline pipeline) {
        super.initPipeline(pipeline);
        if (trafficShapingHandler != null) {
            pipeline.addLast(trafficShapingHandler);
        }
    }

    @Override
    protected void initOptions(ServerBootstrap b) {
        super.initOptions(b);
        if (snd_buf.gateway_server > 0) b.childOption(ChannelOption.SO_SNDBUF, snd_buf.gateway_server);
        if (rcv_buf.gateway_server > 0) b.childOption(ChannelOption.SO_RCVBUF, rcv_buf.gateway_server);
        /**
         * 这个坑其实也不算坑，只是因为懒，该做的事情没做。一般来讲我们的业务如果比较小的时候我们用同步处理，等业务到一定规模的时候，一个优化手段就是异步化。
         * 异步化是提高吞吐量的一个很好的手段。但是，与异步相比，同步有天然的负反馈机制，也就是如果后端慢了，前面也会跟着慢起来，可以自动的调节。
         * 但是异步就不同了，异步就像决堤的大坝一样，洪水是畅通无阻。如果这个时候没有进行有效的限流措施就很容易把后端冲垮。
         * 如果一下子把后端冲垮倒也不是最坏的情况，就怕把后端冲的要死不活。
         * 这个时候，后端就会变得特别缓慢，如果这个时候前面的应用使用了一些无界的资源等，就有可能把自己弄死。
         * 那么现在要介绍的这个坑就是关于Netty里的ChannelOutboundBuffer这个东西的。
         * 这个buffer是用在netty向channel write数据的时候，有个buffer缓冲，这样可以提高网络的吞吐量(每个channel有一个这样的buffer)。
         * 初始大小是32(32个元素，不是指字节)，但是如果超过32就会翻倍，一直增长。
         * 大部分时候是没有什么问题的，但是在碰到对端非常慢(对端慢指的是对端处理TCP包的速度变慢，比如对端负载特别高的时候就有可能是这个情况)的时候就有问题了，
         * 这个时候如果还是不断地写数据，这个buffer就会不断地增长，最后就有可能出问题了(我们的情况是开始吃swap，最后进程被linux killer干掉了)。
         * 为什么说这个地方是坑呢，因为大部分时候我们往一个channel写数据会判断channel是否active，但是往往忽略了这种慢的情况。
         *
         * 那这个问题怎么解决呢？其实ChannelOutboundBuffer虽然无界，但是可以给它配置一个高水位线和低水位线，
         * 当buffer的大小超过高水位线的时候对应channel的isWritable就会变成false，
         * 当buffer的大小低于低水位线的时候，isWritable就会变成true。所以应用应该判断isWritable，如果是false就不要再写数据了。
         * 高水位线和低水位线是字节数，默认高水位是64K，低水位是32K，我们可以根据我们的应用需要支持多少连接数和系统资源进行合理规划。
         */
        if (gateway_server_low > 0 && gateway_server_high > 0) {
            b.childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(
                    gateway_server_low, gateway_server_high
            ));
        }
    }

    @Override
    public ChannelHandler getChannelHandler() {
        return channelHandler;
    }

    @Override
    public ChannelFactory<? extends ServerChannel> getChannelFactory() {
        if (CC.mp.net.tcpGateway()) return super.getChannelFactory();
        if (CC.mp.net.udtGateway()) return NioUdtProvider.BYTE_ACCEPTOR;
        if (CC.mp.net.sctpGateway()) return NioSctpServerChannel::new;
        return super.getChannelFactory();
    }

    @Override
    public SelectorProvider getSelectorProvider() {
        if (CC.mp.net.tcpGateway()) return super.getSelectorProvider();
        if (CC.mp.net.udtGateway()) return NioUdtProvider.BYTE_PROVIDER;
        if (CC.mp.net.sctpGateway()) return super.getSelectorProvider();
        return super.getSelectorProvider();
    }
}
