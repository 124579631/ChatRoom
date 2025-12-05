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
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLException;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 客户端主类 - 安全增强版 (支持密钥持久化)
 */
public class Client {

    private String currentUserId;
    private final KeyPair currentKeyPair;
    private final Map<String, SecretKey> sharedAesKeys = new ConcurrentHashMap<>();

    // 【新增】本地主密钥：由用户登录密码派生，用于加密/解密本地数据库中的会话密钥
    private SecretKey localMasterKey;

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

    /**
     * 【关键修改】设置共享密钥时，自动加密并持久化到本地数据库
     */
    public void setSharedAesKey(String targetId, SecretKey key) {
        sharedAesKeys.put(targetId, key);
        System.out.println("✅ 安全通道建立: " + targetId);

        // 如果已初始化安全存储 (即用户已登录)，则保存密钥
        if (localMasterKey != null && currentUserId != null) {
            saveKeyToDatabase(targetId, key);
        }
    }

    /**
     * 【新增】初始化安全存储 (在登录成功后调用)
     * 1. 根据用户密码生成主密钥
     * 2. 从数据库加载之前的聊天密钥
     */
    public void initSecureStorage(String password) {
        try {
            // 1. 派生主密钥
            this.localMasterKey = EncryptionUtils.deriveKeyFromPassword(password);
            System.out.println("🔐 安全存储已初始化。");

            // 2. 加载本地密钥
            Map<String, String> encryptedKeys = DatabaseManager.getAllSessionKeys(currentUserId);
            int loadedCount = 0;

            for (Map.Entry<String, String> entry : encryptedKeys.entrySet()) {
                String targetId = entry.getKey();
                String encryptedBlob = entry.getValue();

                try {
                    // 使用主密钥解密
                    String keyBase64 = EncryptionUtils.aesDecrypt(encryptedBlob, localMasterKey);
                    byte[] keyBytes = Base64.getDecoder().decode(keyBase64);
                    SecretKey originalKey = new SecretKeySpec(keyBytes, "AES");

                    // 放入内存
                    sharedAesKeys.put(targetId, originalKey);
                    loadedCount++;
                } catch (Exception e) {
                    System.err.println("⚠️ 警告: 无法解密与 " + targetId + " 的密钥 (可能修改了密码?)");
                }
            }
            if (loadedCount > 0) {
                System.out.println("📂 已恢复 " + loadedCount + " 个历史会话密钥。");
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 【新增】将密钥加密存入 DB
     */
    private void saveKeyToDatabase(String targetId, SecretKey key) {
        try {
            // 先将 Key 转为 Base64 字符串
            String keyBase64 = Base64.getEncoder().encodeToString(key.getEncoded());
            // 再用主密钥加密这个字符串
            String encryptedBlob = EncryptionUtils.aesEncrypt(keyBase64, localMasterKey);
            // 存入数据库
            DatabaseManager.saveSessionKey(currentUserId, targetId, encryptedBlob);
            System.out.println("💾 密钥已安全归档 -> DB");
        } catch (Exception e) {
            System.err.println("❌ 密钥归档失败: " + e.getMessage());
        }
    }

    // --- 连接逻辑 (保持之前修复 SSLException 的版本) ---
    public void connect(String host, int port,
                        Consumer<LoginResponse> loginCallback,
                        Consumer<Message> messageCallback) throws InterruptedException, SSLException {
        // ... (保持上一轮修复后的 connect 代码不变)
        this.host = host;
        this.port = port;
        this.loginCallback = loginCallback;
        this.messageCallback = messageCallback;
        this.group = new NioEventLoopGroup();
        try {
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
                            pipeline.addLast(sslCtx.newHandler(ch.alloc(), host, port));
                            pipeline.addLast(new IdleStateHandler(0, 5, 0));
                            pipeline.addLast(new LengthFieldBasedFrameDecoder(1024 * 1024 * 10, 0, 4, 0, 4));
                            pipeline.addLast(new LengthFieldPrepender(4));
                            pipeline.addLast(new MessageToJsonEncoder());
                            pipeline.addLast(new JsonToMessageDecoder());
                            pipeline.addLast(new ChatClientHandler(Client.this, loginCallback, messageCallback));
                        }
                    });
            ChannelFuture f = bootstrap.connect(host, port).sync();
            this.channel = f.channel();
            SslHandler sslHandler = this.channel.pipeline().get(SslHandler.class);
            if (sslHandler != null) {
                sslHandler.handshakeFuture().sync();
            }
            this.channel.closeFuture().addListener(future -> {
                if (!isIntentionalDisconnect) group.schedule(this::doReconnect, 3, TimeUnit.SECONDS);
            });
        } catch (SSLException | InterruptedException e) {
            throw e;
        } catch (Exception e) {
            if (loginCallback != null) loginCallback.accept(new LoginResponse("SYSTEM", false, "连接失败: " + e.getMessage()));
        }
    }

    public synchronized void doReconnect() {
        if (isIntentionalDisconnect) return;
        ChannelFuture f = bootstrap.connect(host, port);
        f.addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                this.channel = future.channel();
                SslHandler sslHandler = this.channel.pipeline().get(SslHandler.class);
                if (sslHandler != null) {
                    sslHandler.handshakeFuture().addListener(handshakeFuture -> {
                        if (handshakeFuture.isSuccess()) {
                            if (messageCallback != null) messageCallback.accept(new TextMessage("SYSTEM", "✅ 网络已恢复"));
                            this.channel.closeFuture().addListener(closeFuture -> {
                                if (!isIntentionalDisconnect) future.channel().eventLoop().schedule(this::doReconnect, 3, TimeUnit.SECONDS);
                            });
                        } else this.channel.close();
                    });
                }
            } else future.channel().eventLoop().schedule(this::doReconnect, 3, TimeUnit.SECONDS);
        });
    }

    public void doConnect() { doReconnect(); }

    public void sendMessage(Message message) {
        if (channel != null && channel.isActive()) channel.writeAndFlush(message);
    }

    public void disconnect() {
        isIntentionalDisconnect = true;
        if (channel != null) channel.close();
        if (group != null) group.shutdownGracefully();
    }

    public void setMessageCallback(Consumer<Message> callback) {
        this.messageCallback = callback;
        if (channel != null && channel.pipeline().get(ChatClientHandler.class) != null) {
            channel.pipeline().get(ChatClientHandler.class).setMessageCallback(callback);
        }
    }
}