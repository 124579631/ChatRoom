package com.my.chatroom;

import com.google.gson.Gson;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import java.util.function.Consumer;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * 客户端消息处理器 (ChatClientHandler) - 修复版
 */
public class ChatClientHandler extends SimpleChannelInboundHandler<Message> {

    private static final Gson GSON = MessageTypeAdapter.createGson();
    private final Client client;

    private final Consumer<LoginResponse> loginCallback;
    private Consumer<Message> messageCallback;

    public ChatClientHandler(Client client,
                             Consumer<LoginResponse> loginCallback,
                             Consumer<Message> messageCallback) {
        this.client = client;
        this.loginCallback = loginCallback;
        this.messageCallback = messageCallback;
    }

    public void setMessageCallback(Consumer<Message> messageCallback) {
        this.messageCallback = messageCallback;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Message msg) throws Exception {
        // 确保消息类型正确
        Message genericMsg = GSON.fromJson(GSON.toJson(msg), Message.class);

        // 1. 登录响应
        if (genericMsg.getType() == Message.MessageType.LOGIN_RESPONSE) {
            if (loginCallback != null) {
                loginCallback.accept((LoginResponse) genericMsg);
            }
            if (((LoginResponse) genericMsg).isSuccess()) {
                client.setCurrentUserId(((LoginResponse) genericMsg).getSenderId());
            }
            return;
        }

        // 2. 密钥交换响应 (主动方收到对方公钥)
        if (genericMsg.getType() == Message.MessageType.KEY_EXCHANGE_RESPONSE) {
            handleKeyExchangeResponse(ctx, (KeyExchangeResponse) genericMsg);
            return;
        }

        // 3. AES 密钥交换 (被动方收到 AES 密钥)
        if (genericMsg.getType() == Message.MessageType.AES_KEY_EXCHANGE) {
            handleAesKeyExchange(ctx, (AESKeyExchangeMessage) genericMsg);
            return;
        }

        // 4. 其他消息 (文本、用户列表、群聊等)
        if (messageCallback != null) {
            messageCallback.accept(genericMsg);
        }
    }

    // --- E2EE 核心逻辑 ---

    private void handleKeyExchangeResponse(ChannelHandlerContext ctx, KeyExchangeResponse response) throws Exception {
        String targetId = response.getTargetUserId();
        if (!response.isSuccess()) {
            System.err.println("❌ 公钥请求失败: " + response.getMessage());
            notifyUI(new TextMessage("SYSTEM", "❌ 无法获取对方公钥: " + response.getMessage()));
            return;
        }

        try {
            // 1. 恢复目标公钥
            java.security.PublicKey targetPublicKey = EncryptionUtils.getPublicKey(response.getTargetPublicKey());
            if (targetPublicKey == null) {
                notifyUI(new TextMessage("SYSTEM", "❌ 公钥格式错误，无法解析。"));
                return;
            }

            // 2. 生成 AES 密钥
            SecretKey aesKey = EncryptionUtils.generateAesKey();

            // 3. 用目标公钥加密 AES 密钥
            String encryptedAesKey = EncryptionUtils.rsaEncrypt(aesKey.getEncoded(), targetPublicKey);

            // 4. 发送给对方
            AESKeyExchangeMessage aesMsg = new AESKeyExchangeMessage(client.getCurrentUserId(), targetId, encryptedAesKey);
            ctx.channel().writeAndFlush(aesMsg);

            // 5. 【关键】保存到本地，并通知 UI
            client.setSharedAesKey(targetId, aesKey);
            System.out.println("[E2EE] 主动握手成功，已保存与 [" + targetId + "] 的密钥。");

            notifyUI(new TextMessage("SYSTEM", "✅ 安全连接已建立 (主动模式)！请重新发送消息。"));

        } catch (Exception e) {
            e.printStackTrace();
            notifyUI(new TextMessage("SYSTEM", "❌ 密钥协商发生异常: " + e.getMessage()));
        }
    }

    private void handleAesKeyExchange(ChannelHandlerContext ctx, AESKeyExchangeMessage aesMsg) throws Exception {
        String senderId = aesMsg.getSenderId();
        String encryptedAesKey = aesMsg.getEncryptedAesKey();

        try {
            // 1. 用自己的私钥解密 AES 密钥
            byte[] decryptedBytes = EncryptionUtils.rsaDecrypt(encryptedAesKey, client.getPrivateKey());
            // 2. 重建 AES 密钥对象
            // 注意：这里通常不需要 copyOf，直接用 decryptedBytes 即可，除非 padding 有问题
            SecretKey aesKey = new SecretKeySpec(decryptedBytes, "AES");

            // 3. 【关键】保存到本地
            client.setSharedAesKey(senderId, aesKey);
            System.out.println("[E2EE] 被动握手成功，收到 [" + senderId + "] 的密钥。");

            notifyUI(new TextMessage("SYSTEM", "✅ 安全连接已建立 (被动模式)！可以开始聊天了。"));

        } catch (Exception e) {
            System.err.println("解密 AES 密钥失败: " + e.getMessage());
            notifyUI(new TextMessage("SYSTEM", "❌ 无法解密对方发来的密钥。"));
        }
    }

    private void notifyUI(Message msg) {
        if (messageCallback != null) {
            messageCallback.accept(msg);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        System.out.println("与服务器断开连接。");
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        ctx.close();
    }
}