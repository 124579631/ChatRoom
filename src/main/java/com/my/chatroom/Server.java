package com.my.chatroom;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;

public class Server {

    private final int port;

    public Server(int port) {
        this.port = port;
    }

    public void run() throws Exception {
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap b = new ServerBootstrap();

            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) throws Exception {
                            ChannelPipeline pipeline = ch.pipeline();

                            // 1. 定长解码器：解决粘包/拆包
                            pipeline.addLast(new LengthFieldBasedFrameDecoder(
                                    1024 * 1024, 0, 4, 0, 4));

                            // 2. 定长编码器：添加4字节的长度头
                            pipeline.addLast(new LengthFieldPrepender(4));

                            // 3. JSON 编解码器：对象 <-> 字节流
                            pipeline.addLast(new MessageToJsonEncoder());
                            pipeline.addLast(new JsonToMessageDecoder());

                            // 4. 业务逻辑处理器
                            pipeline.addLast(new ChatServerHandler());
                        }
                    });

            ChannelFuture f = b.bind(port).sync();
            System.out.println("服务端启动成功，正在监听端口: " + port);

            f.channel().closeFuture().sync();
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }

    public static void main(String[] args) throws Exception {
        // 引用 DatabaseManager 类，触发其静态初始化块，创建数据库和表
        try {
            Class.forName(DatabaseManager.class.getName());
        } catch (ClassNotFoundException e) {
            // 不应发生
        }

        new Server(8888).run();
    }
}