package com.my.chatroom;

import com.google.gson.Gson;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.security.PublicKey;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * 客户端消息处理器 (ChatClientHandler)
 * 作用：处理从服务器接收到的各种消息协议。
 * 【已修改】：新增处理 BurnAfterReadMessage；新增保存 TextMessage 到本地数据库的逻辑。
 */
public class ChatClientHandler extends SimpleChannelInboundHandler<Message> {

    private static final Gson GSON = MessageTypeAdapter.createGson();
    private final Client client;

    public ChatClientHandler(Client client) {
        this.client = client;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Message msg) throws Exception {

        // 关键步骤：通过 Gson 重新转换，确保我们得到正确的子类对象
        Message genericMsg = GSON.fromJson(GSON.toJson(msg), Message.class);

        // --- 1. 处理登录响应 ---
        if (genericMsg.getType() == Message.MessageType.LOGIN_RESPONSE) {
            LoginResponse response = (LoginResponse) genericMsg;
            if (response.isSuccess()) {
                client.setCurrentUserId(response.getSenderId());
                System.out.println("✅ 登录成功！用户ID: " + client.getCurrentUserId());
                System.out.println("请使用 CHAT <targetId> 开始聊天，使用 LIST 查看在线用户，使用 BAR <消息> 发送阅后即焚。");
            } else {
                System.err.println("❌ 登录失败: " + response.getMessage());
            }
            return;
        }

        // --- 2. 处理在线用户列表 ---
        if (genericMsg.getType() == Message.MessageType.USER_LIST_UPDATE) {
            UserListMessage userListMsg = (UserListMessage) genericMsg;
            System.out.println("\n--- 当前在线用户 ---");
            for (String user : userListMsg.getOnlineUsers()) {
                if (!user.equals(client.getCurrentUserId())) { // 不显示自己
                    System.out.println("- " + user);
                }
            }
            System.out.println("--------------------");
            return;
        }

        // --- 3. 处理公钥交换响应 ---
        if (genericMsg.getType() == Message.MessageType.KEY_EXCHANGE_RESPONSE) {
            KeyExchangeResponse response = (KeyExchangeResponse) genericMsg;
            String targetId = response.getTargetUserId();
            String targetPublicKeyBase64 = response.getTargetPublicKey();

            if (!response.isSuccess()) {
                System.err.println("❌ 公钥请求失败: " + response.getMessage());
                return;
            }

            // 1. 恢复公钥对象
            PublicKey targetPublicKey = EncryptionUtils.getPublicKey(targetPublicKeyBase64);
            if (targetPublicKey == null) {
                System.err.println("❌ 公钥恢复失败，无法进行 E2EE。");
                return;
            }

            // 2. 生成 AES 密钥
            SecretKey aesKey = EncryptionUtils.generateAesKey();

            // 3. 用目标公钥 RSA 加密 AES 密钥
            String encryptedAesKey;
            try {
                // Base64 编码的加密内容
                encryptedAesKey = EncryptionUtils.rsaEncrypt(aesKey.getEncoded(), targetPublicKey);
            } catch (Exception e) {
                System.err.println("❌ RSA 加密 AES 密钥失败: " + e.getMessage());
                return;
            }

            // 4. 发送 AES 密钥交换消息给服务器 (服务器会转发给目标用户)
            AESKeyExchangeMessage aesMsg = new AESKeyExchangeMessage(client.getCurrentUserId(), targetId, encryptedAesKey);
            ctx.channel().writeAndFlush(aesMsg);

            // 5. 在本地保存共享密钥
            client.setSharedAesKey(targetId, aesKey); // <--- 已修复

            System.out.println("[E2EE] 已与 [" + targetId + "] 建立共享密钥，并发送了密钥交换消息。");
            return;
        }

        // --- 4. 处理 AES 密钥交换消息 ---
        if (genericMsg.getType() == Message.MessageType.AES_KEY_EXCHANGE) {
            AESKeyExchangeMessage aesMsg = (AESKeyExchangeMessage) genericMsg;
            String senderId = aesMsg.getSenderId();
            String encryptedAesKey = aesMsg.getEncryptedAesKey();

            // 1. 使用自己的私钥解密 AES 密钥
            // 1. 使用自己的私钥解密 AES 密钥
            SecretKey aesKey;
            try {
                // RSA 解密 Base64 后的密文
                byte[] decryptedBytes = EncryptionUtils.rsaDecrypt(encryptedAesKey, client.getPrivateKey()); // <--- 已修复

                // AES 密钥必须是 16 字节，RSA 解密可能会填充
                if (decryptedBytes.length < 16) {
                    throw new Exception("RSA 解密得到的字节长度 (" + decryptedBytes.length + " bytes) 不足 16 字节。");
                }

                // 确保我们只取有效密钥的字节（假设是 16 字节，或使用 Arrays.copyOf 取前 16 字节）
                byte[] cleanKeyBytes = Arrays.copyOf(decryptedBytes, 16);

                // 使用干净的字节数组构建 SecretKey 对象
                aesKey = new SecretKeySpec(cleanKeyBytes, "AES");

            } catch (Exception e) {
                System.err.println("[E2EE 错误] RSA 解密或构建 AES 密钥失败: " + e.getMessage());
                return;
            }

            // 2. 将 AES 密钥保存为共享密钥
            client.setSharedAesKey(senderId, aesKey); // <--- 已修复

            System.out.println("[E2EE] 收到用户 [" + senderId + "] 的共享密钥。现在可以开始与他聊天了。");
            return;
        }

        // --- 5. 处理加密聊天消息 (普通消息) ---
        if (genericMsg.getType() == Message.MessageType.TEXT_MESSAGE_ENCRYPTED) {

            // 只有 TextMessage 和系统消息 (由 ChatServerHandler 广播) 会是这种类型
            if (genericMsg instanceof TextMessage) {
                TextMessage textMsg = (TextMessage) genericMsg;
                String senderId = textMsg.getSenderId();
                String encryptedContent = textMsg.getContent();

                // 检查是否为系统消息
                if (senderId.equals("SYSTEM")) {
                    System.out.println("[系统消息]: " + encryptedContent);
                    return; // 系统消息不进行 E2EE 处理和存储
                }

                // 获取共享密钥
                SecretKey key = client.getSharedAesKey(senderId);

                if (key != null) {
                    try {
                        // 1. 解密消息内容
                        String decryptedContent = EncryptionUtils.aesDecrypt(encryptedContent, key);

                        // 2. 【新增】接收方将加密消息保存到本地历史记录
                        DatabaseManager.saveEncryptedMessage(client.getCurrentUserId(), senderId, false, encryptedContent);

                        System.out.println("\n<<< (收到自 " + senderId + ") " + decryptedContent);

                    } catch (Exception e) {
                        System.err.println("\n<<< (收到自 " + senderId + ") [解密失败]: " + encryptedContent);
                    }
                } else {
                    System.err.println("\n<<< (收到自 " + senderId + ") [无共享密钥，无法解密]: " + encryptedContent);
                }
            }
            return;
        }

        // --- 6. 【新增】处理阅后即焚消息 ---
        if (genericMsg.getType() == Message.MessageType.BURN_AFTER_READ) {
            if (genericMsg instanceof BurnAfterReadMessage) {
                BurnAfterReadMessage burnMsg = (BurnAfterReadMessage) genericMsg;
                String senderId = burnMsg.getSenderId();
                String encryptedContent = burnMsg.getEncryptedContent();

                SecretKey key = client.getSharedAesKey(senderId);

                if (key != null) {
                    try {
                        // 1. 解密消息内容
                        String decryptedContent = EncryptionUtils.aesDecrypt(encryptedContent, key);

                        // 2. 显示消息 (这是唯一一次显示)
                        System.out.println("\n<<< (收到阅后即焚自 " + senderId + ") " + decryptedContent);

                        // 3. 核心：不执行 DatabaseManager.saveEncryptedMessage，实现“阅后即焚”
                        System.out.println("--- [消息已阅，未在本地存储历史记录] ---");

                    } catch (Exception e) {
                        System.err.println("\n<<< (收到阅后即焚自 " + senderId + ") [解密失败]: " + encryptedContent);
                    }
                } else {
                    System.err.println("\n<<< (收到阅后即焚自 " + senderId + ") [无共享密钥，无法解密]: " + encryptedContent);
                }
            }
            return;
        }

        // --- 7. 处理其他/未知消息 ---
        System.out.println("[系统/未知]: 收到未知类型消息: " + genericMsg.getType());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        System.out.println("与服务器的连接已断开。");
        ctx.close();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        System.err.println("客户端处理器发生异常: " + cause.getMessage());
        // cause.printStackTrace(); // 调试时可以打开
        ctx.close();
    }
}