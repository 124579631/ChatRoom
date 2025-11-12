package com.my.chatroom;

import com.google.gson.Gson;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import java.util.function.Consumer; // 【新增】
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
/**
 * 客户端消息处理器 (ChatClientHandler)
 * 【UI 版修改】:
 * 1. 构造函数接收回调，用于向 UI 报告结果。
 * 2. 收到 LoginResponse 时，调用回调，而不是打印到控制台。
 * 3. 收到其他消息 (如 TextMessage) 时，也调用回调。
 */
public class ChatClientHandler extends SimpleChannelInboundHandler<Message> {

    private static final Gson GSON = MessageTypeAdapter.createGson();
    private final Client client;

    // 【新增】回调函数
    private final Consumer<LoginResponse> loginCallback;
    private Consumer<Message> messageCallback;

    // 【修改】构造函数
    public ChatClientHandler(Client client,
                             Consumer<LoginResponse> loginCallback,
                             Consumer<Message> messageCallback) {
        this.client = client;
        this.loginCallback = loginCallback;
        this.messageCallback = messageCallback;
    }

    /**
     * 【新增】允许 ChatController 覆盖消息回调
     */
    public void setMessageCallback(Consumer<Message> messageCallback) {
        this.messageCallback = messageCallback;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Message msg) throws Exception {

        Message genericMsg = GSON.fromJson(GSON.toJson(msg), Message.class);

        // --- 1. 【核心修改】处理登录响应 ---
        if (genericMsg.getType() == Message.MessageType.LOGIN_RESPONSE) {
            LoginResponse response = (LoginResponse) genericMsg;

            // 调用回调，将 response 对象传递回 LoginController
            if (loginCallback != null) {
                loginCallback.accept(response);
            }

            // 如果登录成功，设置 Client 的 UserID
            if (response.isSuccess()) {
                client.setCurrentUserId(response.getSenderId());
            }
            return;
        }

        // --- 2. 【核心修改】处理所有其他消息 ---
        // (包括 TextMessage, UserList, E2EE 响应等)

        // E2EE 逻辑 (如密钥交换) 仍然在此处处理
        if (genericMsg.getType() == Message.MessageType.KEY_EXCHANGE_RESPONSE) {
            handleKeyExchangeResponse(ctx, (KeyExchangeResponse) genericMsg);
            // E2EE 消息通常不需要在主聊天窗口显示，所以我们 return
            return;
        }

        if (genericMsg.getType() == Message.MessageType.AES_KEY_EXCHANGE) {
            handleAesKeyExchange(ctx, (AESKeyExchangeMessage) genericMsg);
            return;
        }

        // 【新增】将其他需要显示的消息 (如 TextMessage) 传递给 UI
        if (messageCallback != null) {
            messageCallback.accept(genericMsg);
        }
    }

    // --- 内部 E2EE 逻辑 (从旧代码复制而来，无变化) ---
    private void handleKeyExchangeResponse(ChannelHandlerContext ctx, KeyExchangeResponse response) throws Exception {
        String targetId = response.getTargetUserId();
        if (!response.isSuccess()) {
            System.err.println("❌ 公钥请求失败: " + response.getMessage());
            return;
        }
        // ... (省略 E2EE 逻辑，和你的旧代码完全一样) ...
        // ... (1. 恢复公钥, 2. 生成AES, 3. 加密AES, 4. 发送AES, 5. 本地保存) ...

        // (为确保完整性，粘贴 E2EE 逻辑)
        java.security.PublicKey targetPublicKey = EncryptionUtils.getPublicKey(response.getTargetPublicKey());
        if (targetPublicKey == null) return;
        SecretKey aesKey = EncryptionUtils.generateAesKey();
        String encryptedAesKey = EncryptionUtils.rsaEncrypt(aesKey.getEncoded(), targetPublicKey);
        AESKeyExchangeMessage aesMsg = new AESKeyExchangeMessage(client.getCurrentUserId(), targetId, encryptedAesKey);
        ctx.channel().writeAndFlush(aesMsg);
        client.setSharedAesKey(targetId, aesKey);
        System.out.println("[E2EE] 已与 [" + targetId + "] 建立共享密钥。");
    }

    private void handleAesKeyExchange(ChannelHandlerContext ctx, AESKeyExchangeMessage aesMsg) throws Exception {
        String senderId = aesMsg.getSenderId();
        String encryptedAesKey = aesMsg.getEncryptedAesKey();
        // ... (省略 E2EE 逻辑，和你的旧代码完全一样) ...
        // ... (1. 私钥解密, 2. 恢复AES, 3. 本地保存) ...

        // (为确保完整性，粘贴 E2EE 逻辑)
        byte[] decryptedBytes = EncryptionUtils.rsaDecrypt(encryptedAesKey, client.getPrivateKey());
        byte[] cleanKeyBytes = java.util.Arrays.copyOf(decryptedBytes, 16);
        SecretKey aesKey = new javax.crypto.spec.SecretKeySpec(cleanKeyBytes, "AES");
        client.setSharedAesKey(senderId, aesKey);
        System.out.println("[E2EE] 收到用户 [" + senderId + "] 的共享密钥。");
    }

    // --- (其他 channelInactive, exceptionCaught 方法保持不变) ---
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        System.out.println("与服务器的连接已断开。");
        // 可以在这里通过 messageCallback 发送一个“断线”消息
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        System.err.println("客户端处理器发生异常: " + cause.getMessage());
        ctx.close();
    }
}