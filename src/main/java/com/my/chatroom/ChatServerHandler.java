package com.my.chatroom;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.concurrent.GlobalEventExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import com.google.gson.Gson;

public class ChatServerHandler extends SimpleChannelInboundHandler<Message> {

    private static final ChannelGroup CHANNELS = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
    private static final Map<String, Channel> LOGGED_IN_USERS = new ConcurrentHashMap<>();
    private static final Gson GSON = MessageTypeAdapter.createGson();

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        CHANNELS.add(ctx.channel());
        System.out.println("[连接] " + ctx.channel().remoteAddress() + " 已连接。");
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Message msg) throws Exception {
        // 确保消息类型正确 (防守式编程，重新解析一次以确保多态正确)
        String json = GSON.toJson(msg);
        Message actualMsg = GSON.fromJson(json, Message.class);

        // 1. 登录请求
        if (actualMsg.getType() == Message.MessageType.LOGIN_REQUEST) {
            handleLoginRequest(ctx, actualMsg);
        }
        // 2. 密钥交换请求 (私聊 E2EE 用)
        else if (actualMsg.getType() == Message.MessageType.KEY_EXCHANGE_REQUEST) {
            handleKeyExchangeRequest(ctx, actualMsg);
        }
        // 3. AES 密钥交换 (私聊 E2EE 用)
        else if (actualMsg.getType() == Message.MessageType.AES_KEY_EXCHANGE) {
            handleAESKeyExchange(ctx, actualMsg);
        }
        // 4. 普通消息 / 阅后即焚 / 图片 (可能是私聊，也可能是群聊)
        else {
            String userId = getUserIdByChannel(ctx.channel());
            if (userId != null) {
                // 【核心修改】这里处理所有类型的转发消息
                handleForwarding(ctx, actualMsg, userId);
            } else {
                ctx.writeAndFlush(new LoginResponse("SERVER", false, "请先登录！"));
            }
        }
    }

    /**
     * 【核心修改】统一转发逻辑，支持私聊和群聊
     * 已修复：添加了 ImageMessage 的支持
     */
    private void handleForwarding(ChannelHandlerContext ctx, Message msg, String senderId) {
        String targetId = null;

        // 1. 提取目标 ID
        if (msg instanceof TextMessage) {
            targetId = ((TextMessage) msg).getTargetUserId();
        } else if (msg instanceof BurnAfterReadMessage) {
            targetId = ((BurnAfterReadMessage) msg).getTargetUserId();
        } else if (msg instanceof ImageMessage) {
            // 【新增】支持图片消息转发
            targetId = ((ImageMessage) msg).getTargetUserId();
        }

        if (targetId != null) {
            // 【新增】群聊广播逻辑：如果是发给 "ALL" 的消息
            if ("ALL".equals(targetId)) {
                broadcastGroupMessage(msg, senderId);
                return;
            }

            // 私聊逻辑
            Channel targetChannel = LOGGED_IN_USERS.get(targetId);
            if (targetChannel != null) {
                targetChannel.writeAndFlush(msg);
                System.out.println("[转发] " + senderId + " -> " + targetId + " (类型: " + msg.getType() + ")");
            } else {
                // 可选：通知发送者目标不在线，但对于图片消息通常静默处理或存离线消息（当前暂不处理）
                System.out.println("[转发失败] 目标 " + targetId + " 不在线");
            }
        }
    }

    private void broadcastGroupMessage(Message msg, String senderId) {
        System.out.println("[群聊] 来自 " + senderId + " 的广播消息");
        for (Channel ch : LOGGED_IN_USERS.values()) {
            // 发给所有人
            ch.writeAndFlush(msg);
        }
    }

    private void handleLoginRequest(ChannelHandlerContext ctx, Message msg) {
        if (!(msg instanceof LoginRequest)) return;
        LoginRequest request = (LoginRequest) msg;

        String userId = request.getSenderId();
        String password = request.getPassword();
        String publicKey = request.getPublicKey();
        Channel incoming = ctx.channel();
        LoginResponse response;

        if (LOGGED_IN_USERS.containsKey(userId)) {
            response = new LoginResponse(userId, false, "用户已在线，请勿重复登录。");
        } else {
            User user = DatabaseManager.getUser(userId);
            String inputHash = DatabaseManager.hashPassword(password);

            if (user != null && user.getPasswordHash().equals(inputHash)) {
                // 登录成功
                LOGGED_IN_USERS.put(userId, incoming);
                response = new LoginResponse(userId, true, "登录成功！");
                DatabaseManager.updatePublicKey(userId, publicKey);

                System.out.println("[认证成功] " + userId);
                ctx.executor().schedule(this::broadcastUserList, 300, TimeUnit.MILLISECONDS);

            } else if (user == null) {
                // 自动注册
                if (DatabaseManager.registerUser(userId, password)) {
                    LOGGED_IN_USERS.put(userId, incoming);
                    response = new LoginResponse(userId, true, "注册并登录成功！");
                    DatabaseManager.updatePublicKey(userId, publicKey);

                    System.out.println("[注册成功] " + userId);
                    ctx.executor().schedule(this::broadcastUserList, 300, TimeUnit.MILLISECONDS);
                } else {
                    response = new LoginResponse(userId, false, "注册失败。");
                }
            } else {
                response = new LoginResponse(userId, false, "密码错误。");
            }
        }
        incoming.writeAndFlush(response);
    }

    private void handleKeyExchangeRequest(ChannelHandlerContext ctx, Message msg) {
        KeyExchangeRequest request = (KeyExchangeRequest) msg;
        String senderId = request.getSenderId();
        String targetId = request.getTargetUserId();

        System.out.println("[密钥请求] " + senderId + " -> " + targetId);

        KeyExchangeResponse response;
        User targetUser = DatabaseManager.getUser(targetId);

        if (targetUser != null && targetUser.getPublicKey() != null) {
            response = new KeyExchangeResponse(senderId, true, "成功", targetId, targetUser.getPublicKey());
        } else {
            response = new KeyExchangeResponse(senderId, false, "用户不存在或无公钥", targetId, null);
        }
        ctx.writeAndFlush(response);
    }

    private void handleAESKeyExchange(ChannelHandlerContext ctx, Message msg) {
        AESKeyExchangeMessage exchangeMsg = (AESKeyExchangeMessage) msg;
        String targetId = exchangeMsg.getTargetUserId();
        Channel targetChannel = LOGGED_IN_USERS.get(targetId);

        if (targetChannel != null) {
            targetChannel.writeAndFlush(exchangeMsg);
            System.out.println("[密钥传递] -> " + targetId);
        }
    }

    private void broadcastUserList() {
        List<String> onlineUsers = new ArrayList<>(LOGGED_IN_USERS.keySet());
        UserListMessage listMsg = new UserListMessage(onlineUsers);
        for (Channel ch : LOGGED_IN_USERS.values()) {
            if (ch.isActive()) ch.writeAndFlush(listMsg);
        }
    }

    private String getUserIdByChannel(Channel channel) {
        return LOGGED_IN_USERS.entrySet().stream()
                .filter(entry -> entry.getValue() == channel)
                .map(Map.Entry::getKey).findFirst().orElse(null);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        ctx.close();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        String userId = getUserIdByChannel(ctx.channel());
        if (userId != null) {
            LOGGED_IN_USERS.remove(userId);
            broadcastUserList();
            System.out.println("[下线] " + userId);
        }
        CHANNELS.remove(ctx.channel());
    }
}