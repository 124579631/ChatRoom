package com.my.chatroom;

import com.google.gson.Gson;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;

import java.util.function.Consumer;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * 客户端处理器 - 增强版 (支持心跳与断线检测)
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
        // 深拷贝/类型转换确保多态正确
        Message genericMsg = GSON.fromJson(GSON.toJson(msg), Message.class);

        if (genericMsg.getType() == Message.MessageType.LOGIN_RESPONSE) {
            if (loginCallback != null) {
                loginCallback.accept((LoginResponse) genericMsg);
            }
            if (((LoginResponse) genericMsg).isSuccess()) {
                client.setCurrentUserId(((LoginResponse) genericMsg).getSenderId());
            }
            return;
        }

        if (genericMsg.getType() == Message.MessageType.KEY_EXCHANGE_RESPONSE) {
            handleKeyExchangeResponse(ctx, (KeyExchangeResponse) genericMsg);
            return;
        }

        if (genericMsg.getType() == Message.MessageType.AES_KEY_EXCHANGE) {
            handleAesKeyExchange(ctx, (AESKeyExchangeMessage) genericMsg);
            return;
        }

        if (messageCallback != null) {
            messageCallback.accept(genericMsg);
        }
    }

    /**
     * 【新增】捕获用户事件，处理心跳
     */
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent event = (IdleStateEvent) evt;
            if (event.state() == IdleState.WRITER_IDLE) {
                // 如果 5 秒没写数据，发送一个心跳包
                // System.out.println("发送心跳保活...");
                ctx.writeAndFlush(new Message(Message.MessageType.HEARTBEAT));
            }
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }

    /**
     * 【修改】连接断开时，触发 Client 的重连逻辑
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        System.out.println("与服务器断开连接。");
        notifyUI(new TextMessage("SYSTEM", "❌ 与服务器连接中断，尝试重连中..."));

        // 触发 Client 类的重连逻辑
        client.doConnect();
    }

    // --- E2EE 逻辑 (保持不变) ---
    private void handleKeyExchangeResponse(ChannelHandlerContext ctx, KeyExchangeResponse response) throws Exception {
        String targetId = response.getTargetUserId();
        if (!response.isSuccess()) {
            notifyUI(new TextMessage("SYSTEM", "❌ 无法获取对方公钥: " + response.getMessage()));
            return;
        }
        try {
            java.security.PublicKey targetPublicKey = EncryptionUtils.getPublicKey(response.getTargetPublicKey());
            SecretKey aesKey = EncryptionUtils.generateAesKey();
            String encryptedAesKey = EncryptionUtils.rsaEncrypt(aesKey.getEncoded(), targetPublicKey);

            AESKeyExchangeMessage aesMsg = new AESKeyExchangeMessage(client.getCurrentUserId(), targetId, encryptedAesKey);
            ctx.channel().writeAndFlush(aesMsg);

            client.setSharedAesKey(targetId, aesKey);
            notifyUI(new TextMessage("SYSTEM", "✅ 安全连接已建立 (主动模式)"));
        } catch (Exception e) {
            notifyUI(new TextMessage("SYSTEM", "❌ 密钥协商异常: " + e.getMessage()));
        }
    }

    private void handleAesKeyExchange(ChannelHandlerContext ctx, AESKeyExchangeMessage aesMsg) throws Exception {
        String senderId = aesMsg.getSenderId();
        try {
            byte[] decryptedBytes = EncryptionUtils.rsaDecrypt(aesMsg.getEncryptedAesKey(), client.getPrivateKey());
            SecretKey aesKey = new SecretKeySpec(decryptedBytes, "AES");
            client.setSharedAesKey(senderId, aesKey);
            notifyUI(new TextMessage("SYSTEM", "✅ 安全连接已建立 (被动模式)"));
        } catch (Exception e) {
            notifyUI(new TextMessage("SYSTEM", "❌ 无法解密对方密钥"));
        }
    }

    private void notifyUI(Message msg) {
        if (messageCallback != null) {
            messageCallback.accept(msg);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        ctx.close();
    }
}