package com.my.chatroom;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer; // 【新增】用于回调
import javax.crypto.SecretKey;

/**
 * 客户端主类 (Client)
 * 【UI 版修改】: 移除了 main 方法和 start() 命令行循环。
 * 新增 connect() 方法，用于从 UI (LoginController) 启动。
 */
public class Client {

    private String currentUserId; // 当前登录用户 ID
    private final KeyPair currentKeyPair;
    private final Map<String, SecretKey> sharedAesKeys = new ConcurrentHashMap<>();

    // 【新增】存储 Netty 的 Channel 和 EventLoopGroup，以便后续关闭
    private Channel channel;
    private EventLoopGroup group;

    public Client() {
        try {
            this.currentKeyPair = EncryptionUtils.generateRsaKeyPair();
        } catch (java.security.NoSuchAlgorithmException e) {
            System.err.println("❌ 严重错误：无法初始化 RSA 密钥生成器。");
            e.printStackTrace();
            throw new RuntimeException("无法启动客户端：缺少 RSA 算法", e);
        }
    }

    // --- Getter 和 Setter (无变化) ---
    public String getCurrentUserId() { return currentUserId; }
    public void setCurrentUserId(String currentUserId) { this.currentUserId = currentUserId; }
    public PrivateKey getPrivateKey() { return currentKeyPair.getPrivate(); }

    // 获取公钥的方法
    public java.security.PublicKey getPublicKey() {
        return currentKeyPair.getPublic();
    }

    public SecretKey getSharedAesKey(String targetId) { return sharedAesKeys.get(targetId); }
    public void setSharedAesKey(String targetId, SecretKey key) {
        sharedAesKeys.put(targetId, key);
        System.out.println("✅ 已与用户 " + targetId + " 成功建立安全会话 (AES密钥已共享并存储)。");
    }

    /**
     * 【核心修改】连接到服务器
     * @param host 服务器地址
     * @param port 服务器端口
     * @param loginCallback 登录回调 (用于将 LoginResponse 传回 UI 线程)
     * @param messageCallback 消息回调 (用于将收到的消息传回 UI 线程)
     * @throws Exception
     */
    public void connect(String host, int port,
                        Consumer<LoginResponse> loginCallback,
                        Consumer<Message> messageCallback) throws InterruptedException {

        this.group = new NioEventLoopGroup();

        try {
            Bootstrap b = new Bootstrap();
            b.group(group)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) throws Exception {
                            ChannelPipeline pipeline = ch.pipeline();
                            pipeline.addLast(new LengthFieldBasedFrameDecoder(1024 * 1024, 0, 4, 0, 4));
                            pipeline.addLast(new LengthFieldPrepender(4));
                            pipeline.addLast(new MessageToJsonEncoder());
                            pipeline.addLast(new JsonToMessageDecoder());

                            // 【核心修改】将回调传入 Handler
                            pipeline.addLast(new ChatClientHandler(Client.this, loginCallback, messageCallback));
                        }
                    });

            ChannelFuture f = b.connect(host, port).sync();
            System.out.println("成功连接到服务器: " + host + ":" + port);

            // 【新增】保存 channel 实例
            this.channel = f.channel();

            // 保持连接，但不阻塞主线程
            // f.channel().closeFuture().sync(); // 移除这个阻塞调用

        } catch (InterruptedException e) {
            // 如果连接被中断
            System.err.println("连接被中断。");
            group.shutdownGracefully();
            throw e;
        } catch (Exception e) {
            // 其他连接错误
            System.err.println("连接失败：" + e.getMessage());
            group.shutdownGracefully();
            throw new RuntimeException("连接失败", e);
        }

        // 注意：此时 connect 方法会结束，但 Netty 的 NioEventLoopGroup 线程仍在后台运行。
    }

    /**
     * 【新增】发送消息 (UI 调用)
     */
    public void sendMessage(Message message) {
        if (channel != null && channel.isActive()) {
            channel.writeAndFlush(message);
        } else {
            System.err.println("❌ 无法发送消息：连接未激活。");
        }
    }

    /**
     * 【新增】关闭连接 (UI 调用)
     */
    public void disconnect() {
        if (channel != null) {
            channel.close();
        }
        if (group != null) {
            group.shutdownGracefully();
        }
        System.out.println("已与服务器断开连接。");
    }

    /**
     * 【新增】设置消息回调 (由 ChatController 调用)
     * 这会找到 Netty 管道中的 Handler，并更新其回调函数
     */
    public void setMessageCallback(Consumer<Message> callback) {
        if (channel != null && channel.pipeline().get(ChatClientHandler.class) != null) {
            // 找到 handler 并调用它的 setter
            channel.pipeline().get(ChatClientHandler.class).setMessageCallback(callback);
            System.out.println("[Client] 消息回调已成功切换到 ChatController。");
        } else {
            System.err.println("❌ 无法设置消息回调：Channel 或 Handler 未初始化。");
        }
    }
}