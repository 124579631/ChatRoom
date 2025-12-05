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
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;

public class Server {

    private final int port;

    public Server(int port) {
        this.port = port;
    }

    public void run() throws Exception {
        // 1. 生成自签名证书
        SelfSignedCertificate ssc = new SelfSignedCertificate();

        // 2. 配置 SSL 上下文 (强制使用 TLSv1.2 以避免协议不匹配)
        SslContext sslCtx = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey())
                .protocols("TLSv1.2")
                .build();

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

                            // A. SSL 加密层 (最先处理)
                            pipeline.addLast(sslCtx.newHandler(ch.alloc()));

                            // B. 编解码 (支持大文件，最大 10MB)
                            pipeline.addLast(new LengthFieldBasedFrameDecoder(1024 * 1024 * 10, 0, 4, 0, 4));
                            pipeline.addLast(new LengthFieldPrepender(4));
                            pipeline.addLast(new MessageToJsonEncoder());
                            pipeline.addLast(new JsonToMessageDecoder());

                            // C. 业务逻辑
                            pipeline.addLast(new ChatServerHandler());
                        }
                    });

            ChannelFuture f = b.bind(port).sync();
            System.out.println("服务端启动成功 (SSL/TLSv1.2 开启)，监听端口: " + port);
            f.channel().closeFuture().sync();
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }

    public static void main(String[] args) throws Exception {
        // 确保数据库加载
        try { Class.forName(DatabaseManager.class.getName()); } catch (ClassNotFoundException e) {}
        new Server(8888).run();
    }
}