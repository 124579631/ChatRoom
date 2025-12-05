package com.my.chatroom;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.timeout.IdleStateHandler;

import javax.crypto.SecretKey;
import javax.net.ssl.SSLException; // 【关键修复】导入 SSLException
import java.security.KeyPair;
import java.security.PrivateKey;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 客户端主类 - 修复 SSLException 版
 */
public class Client {

    private String currentUserId;
    private final KeyPair currentKeyPair;
    private final Map<String, SecretKey> sharedAesKeys = new ConcurrentHashMap<>();

    private Channel channel;
    private EventLoopGroup group;
    private Bootstrap bootstrap;

    private Consumer<LoginResponse> loginCallback;
    private Consumer<Message> messageCallback;

    private String host;
    private int port;
    private boolean isIntentionalDisconnect = false;

    public Client() {
        try {
            this.currentKeyPair = EncryptionUtils.generateRsaKeyPair();
        } catch (Exception e) {
            throw new RuntimeException("无法初始化 RSA", e);
        }
    }

    // --- Getter / Setter ---
    public String getCurrentUserId() { return currentUserId; }
    public void setCurrentUserId(String currentUserId) { this.currentUserId = currentUserId; }
    public PrivateKey getPrivateKey() { return currentKeyPair.getPrivate(); }
    public java.security.PublicKey getPublicKey() { return currentKeyPair.getPublic(); }
    public SecretKey getSharedAesKey(String targetId) { return sharedAesKeys.get(targetId); }
    public void setSharedAesKey(String targetId, SecretKey key) {
        sharedAesKeys.put(targetId, key);
        System.out.println("✅ 安全通道建立: " + targetId);
    }

    /**
     * 连接逻辑 (同步等待 TCP + SSL 握手)
     * 【关键修改】抛出 SSLException 以便上层处理
     */
    public void connect(String host, int port,
                        Consumer<LoginResponse> loginCallback,
                        Consumer<Message> messageCallback) throws InterruptedException, SSLException {
        this.host = host;
        this.port = port;
        this.loginCallback = loginCallback;
        this.messageCallback = messageCallback;
        this.group = new NioEventLoopGroup();

        try {
            // 1. 配置 SSL: 强制 TLSv1.2，信任自签名证书
            // 【注意】build() 方法会抛出 SSLException
            final SslContext sslCtx = SslContextBuilder.forClient()
                    .protocols("TLSv1.2")
                    .trustManager(InsecureTrustManagerFactory.INSTANCE)
                    .build();

            bootstrap = new Bootstrap();
            bootstrap.group(group)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) throws Exception {
                            ChannelPipeline pipeline = ch.pipeline();
                            // SSL 必须在最前面
                            pipeline.addLast(sslCtx.newHandler(ch.alloc(), host, port));

                            // 心跳检测 (5秒)
                            pipeline.addLast(new IdleStateHandler(0, 5, 0));

                            // 编解码
                            pipeline.addLast(new LengthFieldBasedFrameDecoder(1024 * 1024 * 10, 0, 4, 0, 4));
                            pipeline.addLast(new LengthFieldPrepender(4));
                            pipeline.addLast(new MessageToJsonEncoder());
                            pipeline.addLast(new JsonToMessageDecoder());

                            // 业务 Handler
                            pipeline.addLast(new ChatClientHandler(Client.this, loginCallback, messageCallback));
                        }
                    });

            System.out.println("🔄 正在连接 " + host + ":" + port + " ...");

            // 2. 同步等待 TCP 连接建立
            ChannelFuture f = bootstrap.connect(host, port).sync();
            this.channel = f.channel();

            // 3. 【核心修复】同步等待 SSL 握手完成
            // 如果不加这一步，直接发数据会导致 SSLException 或连接关闭
            SslHandler sslHandler = this.channel.pipeline().get(SslHandler.class);
            if (sslHandler != null) {
                System.out.println("🔐 正在进行 SSL 握手...");
                sslHandler.handshakeFuture().sync();
                System.out.println("✅ SSL 握手成功！");
            }

            // 4. 设置断线监听 (用于自动重连)
            this.channel.closeFuture().addListener(future -> {
                if (!isIntentionalDisconnect) {
                    System.out.println("⚠️ 连接断开，3秒后尝试重连...");
                    group.schedule(this::doReconnect, 3, TimeUnit.SECONDS);
                }
            });

        } catch (SSLException | InterruptedException e) {
            // 抛出特定的 checked exceptions 给上层
            throw e;
        } catch (Exception e) {
            System.err.println("❌ 连接失败: " + e.getMessage());
            // 通知 UI (如果有 generic 错误)
            if (loginCallback != null) {
                loginCallback.accept(new LoginResponse("SYSTEM", false, "连接失败: " + e.getMessage()));
            }
        }
    }

    /**
     * 自动重连逻辑
     */
    public synchronized void doReconnect() {
        if (isIntentionalDisconnect) return;

        System.out.println("🔄 正在尝试重连...");
        // 重新连接逻辑，注意这里是异步的，不抛出 Checked Exception
        ChannelFuture f = bootstrap.connect(host, port);
        f.addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                this.channel = future.channel();

                SslHandler sslHandler = this.channel.pipeline().get(SslHandler.class);
                if (sslHandler != null) {
                    sslHandler.handshakeFuture().addListener(handshakeFuture -> {
                        if (handshakeFuture.isSuccess()) {
                            System.out.println("✅ 重连并握手成功!");
                            if (messageCallback != null) {
                                messageCallback.accept(new TextMessage("SYSTEM", "✅ 网络已恢复"));
                            }
                            // 重新绑定断开监听
                            this.channel.closeFuture().addListener(closeFuture -> {
                                if (!isIntentionalDisconnect) {
                                    future.channel().eventLoop().schedule(this::doReconnect, 3, TimeUnit.SECONDS);
                                }
                            });
                        } else {
                            System.out.println("❌ 重连后 SSL 握手失败");
                            this.channel.close();
                        }
                    });
                }
            } else {
                System.out.println("❌ 重连 TCP 失败，3秒后重试...");
                future.channel().eventLoop().schedule(this::doReconnect, 3, TimeUnit.SECONDS);
            }
        });
    }

    // 为了兼容 ChatClientHandler 的调用
    public void doConnect() {
        doReconnect();
    }

    public void sendMessage(Message message) {
        if (channel != null && channel.isActive()) {
            channel.writeAndFlush(message);
        } else {
            System.err.println("❌ 发送失败：连接未激活");
        }
    }

    public void disconnect() {
        isIntentionalDisconnect = true;
        if (channel != null) channel.close();
        if (group != null) group.shutdownGracefully();
        System.out.println("已断开连接。");
    }

    public void setMessageCallback(Consumer<Message> callback) {
        this.messageCallback = callback;
        if (channel != null && channel.pipeline().get(ChatClientHandler.class) != null) {
            channel.pipeline().get(ChatClientHandler.class).setMessageCallback(callback);
        }
    }
}