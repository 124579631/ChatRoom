package com.my.chatroom;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.List;

/**
 * 客户端主类 (Client)
 * 作用：处理 Netty 连接、用户登录、密钥管理、消息发送/接收的命令行界面。
 * 【已集成】：阅后即焚 (BAR) 和 本地加密历史记录 (HISTORY)。
 */
public class Client {

    private final String host;
    private final int port;

    private String currentUserId; // 当前登录用户 ID
    private String currentChatTarget; // 当前聊天的目标用户 ID (用于简化输入)

    // E2EE 核心：当前客户端的 RSA 密钥对
    private final KeyPair currentKeyPair;
    // E2EE 核心：与各个目标用户共享的对称 AES 密钥
    private final Map<String, SecretKey> sharedAesKeys = new ConcurrentHashMap<>();

    public Client(String host, int port) {
        this.host = host;
        this.port = port;
        try {
            // 在客户端启动时生成自己的 RSA 密钥对
            this.currentKeyPair = EncryptionUtils.generateRsaKeyPair();
        } catch (java.security.NoSuchAlgorithmException e) {
            // 这是一个严重错误，如果发生，客户端无法继续
            System.err.println("❌ 严重错误：无法初始化 RSA 密钥生成器。");
            e.printStackTrace();
            // 抛出运行时异常以停止客户端启动
            throw new RuntimeException("无法启动客户端：缺少 RSA 算法", e);
        }
    }

    // --- Getter 和 Setter ---

    public String getCurrentUserId() {
        return currentUserId;
    }

    public void setCurrentUserId(String currentUserId) {
        this.currentUserId = currentUserId;
    }

    public PrivateKey getPrivateKey() {
        return currentKeyPair.getPrivate();
    }

    public SecretKey getSharedAesKey(String targetId) {
        return sharedAesKeys.get(targetId);
    }

    public void setSharedAesKey(String targetId, SecretKey key) {
        sharedAesKeys.put(targetId, key);
        System.out.println("✅ 已与用户 " + targetId + " 成功建立安全会话 (AES密钥已共享并存储)。");
    }

    /**
     * Netty 客户端启动逻辑
     */
    public void run() throws Exception {
        EventLoopGroup group = new NioEventLoopGroup();
        try {
            Bootstrap b = new Bootstrap();
            b.group(group)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<SocketChannel>() {
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
                            // 将当前 Client 实例传入，方便 Handler 操作密钥和数据库
                            pipeline.addLast(new ChatClientHandler(Client.this));
                        }
                    });

            ChannelFuture f = b.connect(host, port).sync();
            System.out.println("成功连接到服务器: " + host + ":" + port);

            start(f.channel()); // 启动命令行输入循环

            f.channel().closeFuture().sync();
        } finally {
            group.shutdownGracefully();
        }
    }

    /**
     * 命令行输入循环
     */
    private void start(Channel channel) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        System.out.println("\n请输入命令: REGISTER <id> <password> 或 LOGIN <id> <password>");

        while (true) {
            System.out.print("> ");
            String line = in.readLine();
            if (line == null || line.trim().isEmpty()) {
                continue;
            }

            // --- 核心命令解析 ---
            String trimmedLine = line.trim();
            String command = trimmedLine.toUpperCase().split(" ")[0];

            if (command.equals("EXIT")) {
                channel.close();
                break;
            }

            else if (command.equals("REGISTER")) {
                handleRegisterCommand(channel, trimmedLine);
            }

            else if (command.equals("LOGIN")) {
                handleLoginCommand(channel, trimmedLine);
            }

            // 【集成 BAR】阅后即焚命令 BAR <targetId> <messageContent>
            else if (command.equals("BAR")) {
                handleBurnAfterReadCommand(channel, trimmedLine);
            }

            // 【集成 HISTORY】历史记录命令 HISTORY <targetId>
            else if (command.equals("HISTORY")) {
                handleHistoryCommand(trimmedLine);
            }

            // CHAT 命令用于切换聊天目标
            else if (command.equals("CHAT")) {
                handleChatTargetCommand(trimmedLine);
            }

            // 默认：发送加密消息给当前目标用户
            else if (currentUserId != null && currentChatTarget != null) {
                // 如果已登录且设置了聊天目标，直接将输入作为消息内容发送
                handleChatMessage(channel, currentUserId, trimmedLine);
            }

            else if (currentUserId != null && currentChatTarget == null) {
                System.out.println("请先使用 CHAT <targetId> 切换聊天对象。");
            }

            else {
                System.out.println("未知命令或未登录。");
            }
        }
    }

    // --- 命令处理方法：基础认证 ---

    // Client.java - 【替换这个方法】
    private void handleRegisterCommand(Channel channel, String line) {
        String[] parts = line.split(" ");
        if (parts.length == 3) {
            String userId = parts[1];
            String password = parts[2];

            // 【修复】 registerUser 只接受2个参数，删除 null
            boolean success = DatabaseManager.registerUser(userId, password);

            if (success) {
                System.out.println("✅ 注册成功，请重新 LOGIN。");
            } else {
                System.out.println("❌ 注册失败，用户可能已存在。");
            }
        } else {
            System.out.println("格式错误: REGISTER <id> <password>");
        }
    }

    private void handleLoginCommand(Channel channel, String line) {
        String[] parts = line.split(" ");
        if (parts.length == 3) {
            String userId = parts[1];
            String password = parts[2];

            if (!DatabaseManager.verifyPassword(userId, password)) {
                System.out.println("❌ 登录失败：用户名或密码错误。");
                return;
            }

            String publicKeyBase64 = Base64.getEncoder().encodeToString(currentKeyPair.getPublic().getEncoded());
            LoginRequest request = new LoginRequest(userId, password, publicKeyBase64);
            channel.writeAndFlush(request);

            System.out.println(">>> (正在请求登录并上传公钥...)");

        } else {
            System.out.println("格式错误: LOGIN <id> <password>");
        }
    }

    private void handleChatTargetCommand(String line) {
        String[] parts = line.split(" ");
        if (parts.length == 2) {
            currentChatTarget = parts[1];
            System.out.println("--- 当前聊天目标已切换为: " + currentChatTarget + " ---");
            System.out.println("可以直接输入消息发送，或输入 HISTORY 查看记录。");
        } else {
            System.out.println("格式错误: CHAT <targetId>");
        }
    }

    // --- 命令处理方法：核心功能集成 ---

    /**
     * 【核心】处理普通聊天消息的发送，包含 E2EE 加密和本地存储
     */
    private void handleChatMessage(Channel channel, String senderId, String content) {
        if (currentChatTarget == null) {
            System.out.println("请先使用 CHAT <targetId> 设置聊天目标。");
            return;
        }

        SecretKey sharedKey = sharedAesKeys.get(currentChatTarget);

        if (sharedKey == null) {
            // 如果没有共享密钥，则发起密钥交换请求 (E2EE 初始化)
            System.out.println("⚠️ 正在尝试与 " + currentChatTarget + " 建立安全会话 (请求公钥)...");
            KeyExchangeRequest request = new KeyExchangeRequest(senderId, currentChatTarget);
            channel.writeAndFlush(request);

            System.out.println("请等待对方响应并建立安全会话后再发送消息。");
            return;
        }

        try {
            // 1. 使用共享 AES 密钥加密消息内容
            String encryptedContent = EncryptionUtils.aesEncrypt(content, sharedKey);

            // 2. 构造加密的 TextMessage
            TextMessage encryptedMsg = new TextMessage(senderId, encryptedContent);
            encryptedMsg.setTargetUserId(currentChatTarget);

            // 3. 发送给服务器 (服务器只转发密文)
            channel.writeAndFlush(encryptedMsg);
            System.out.println(">>> (已加密发送给 " + currentChatTarget + ") " + content);

            // 4. 发送方将加密消息保存到本地历史记录 (is_sender=true)
            DatabaseManager.saveEncryptedMessage(senderId, currentChatTarget, true, encryptedContent);

        } catch (Exception e) {
            System.err.println("❌ 发送加密消息失败: " + e.getMessage());
        }
    }


    /**
     * 【核心】处理发送阅后即焚消息的逻辑 (BAR)
     * 命令格式：BAR <targetId> <messageContent>
     */
    private void handleBurnAfterReadCommand(Channel channel, String line) {
        if (currentUserId == null) {
            System.out.println("请先登录。");
            return;
        }

        String[] parts = line.split(" ", 3);
        if (parts.length != 3) {
            System.out.println("格式错误: BAR <targetId> <messageContent>");
            return;
        }

        String targetId = parts[1];
        String content = parts[2];

        SecretKey sharedKey = sharedAesKeys.get(targetId);

        if (sharedKey == null) {
            System.out.println("⚠️ 与用户 " + targetId + " 尚未建立安全会话 (无共享密钥)。请尝试发送普通消息触发密钥交换。");
            return;
        }

        try {
            String encryptedContent = EncryptionUtils.aesEncrypt(content, sharedKey);

            // 1. 构建 BurnAfterReadMessage 协议对象
            BurnAfterReadMessage barMsg = new BurnAfterReadMessage(currentUserId, encryptedContent);
            barMsg.setTargetUserId(targetId);

            // 2. 发送到服务器
            channel.writeAndFlush(barMsg);

            System.out.println(">>> (已加密发送阅后即焚给 " + targetId + ") " + content);

            // 3. 核心：阅后即焚消息，发送方也不保存到本地历史记录
            System.out.println("--- [发送方不保存此阅后即焚消息到本地历史] ---");

        } catch (Exception e) {
            System.err.println("❌ 发送阅后即焚消息失败: " + e.getMessage());
        }
    }

    /**
     * 【核心】处理加载本地加密聊天记录的逻辑 (HISTORY)
     * 命令格式：HISTORY <targetId>
     * 注：由于 CHAT 命令已设置当前目标，这里简化为 HISTORY
     */
    private void handleHistoryCommand(String line) {
        if (currentUserId == null) {
            System.out.println("请先登录。");
            return;
        }

        String[] parts = line.split(" ");
        String targetId = (parts.length == 2) ? parts[1] : currentChatTarget;

        if (targetId == null) {
            System.out.println("格式错误: HISTORY <targetId> 或请先使用 CHAT 命令设置聊天对象。");
            return;
        }

        SecretKey sharedKey = sharedAesKeys.get(targetId);

        if (sharedKey == null) {
            System.out.println("⚠️ 与用户 " + targetId + " 尚未建立安全会话 (无共享密钥)。无法解密历史记录。");
            return;
        }

        System.out.println("\n--- 正在加载与 " + targetId + " 的本地加密历史记录 ---");
        List<String[]> history = DatabaseManager.getEncryptedHistory(currentUserId, targetId);

        if (history.isEmpty()) {
            System.out.println("[历史记录] 未找到记录。");
            return;
        }

        for (String[] record : history) {
            String isSender = record[0];
            String encryptedContent = record[1];
            String timestamp = record[2];

            try {
                String decryptedContent = EncryptionUtils.aesDecrypt(encryptedContent, sharedKey);

                String prefix = (isSender.equals("1") ? "我" : targetId) +
                        " (" + timestamp + "): ";
                System.out.println(prefix + decryptedContent);

            } catch (Exception e) {
                System.err.println("[历史记录] 记录解密失败 (可能是密钥已过期或错误): " + encryptedContent);
            }
        }
        System.out.println("--- 历史记录加载完成 ---\n");
    }
    /*
    public static void main(String[] args) throws Exception {
        // 确保 DatabaseManager 被初始化，创建数据库和表
        try {
            Class.forName(DatabaseManager.class.getName());
            // 【修复】删除 DatabaseManager.getConnection(); 这一行
            // Class.forName 已经触发了 static 块中的 initializeDatabase()。
        } catch (Exception e) {
            System.err.println("❌ 数据库初始化失败，请检查 SQLite 驱动和 DatabaseManager.java 文件。");
            e.printStackTrace();
            return;
        }

        String host = "127.0.0.1";
        int port = 8888;

        if (args.length == 2) {
            host = args[0];
            port = Integer.parseInt(args[1]);
        }

        new Client(host, port).run();
    }*/
}