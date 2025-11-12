package com.my.chatroom;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.concurrent.GlobalEventExecutor;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

import com.google.gson.Gson; // 确保导入 Gson

public class ChatServerHandler extends SimpleChannelInboundHandler<Message> {

    private static final ChannelGroup CHANNELS = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
    private static final Map<String, Channel> LOGGED_IN_USERS = new ConcurrentHashMap<>();

    // GSON 实例用于二次反序列化，确保获取正确的子类类型
    private static final Gson GSON = MessageTypeAdapter.createGson();

    // 1. 连接激活
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        CHANNELS.add(ctx.channel());
        System.out.println("[系统日志] " + ctx.channel().remoteAddress() + " 连接，待认证。");
    }

    // 2. 接收 Message 并处理
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Message msg) throws Exception {
        Channel incoming = ctx.channel();

        // 关键：通过 GSON 重新转换，确保我们得到正确的子类对象，解决 Netty 管道的类型冲突
        String json = GSON.toJson(msg);
        Message actualMsg = GSON.fromJson(json, Message.class);

        if (actualMsg.getType() == Message.MessageType.LOGIN_REQUEST) {
            handleLoginRequest(ctx, actualMsg);
        }
        else if (actualMsg.getType() == Message.MessageType.KEY_EXCHANGE_REQUEST) {
            handleKeyExchangeRequest(ctx, actualMsg);
        }
        else if (actualMsg.getType() == Message.MessageType.AES_KEY_EXCHANGE) {
            handleAESKeyExchange(ctx, actualMsg);
        }
        else {
            String userId = LOGGED_IN_USERS.entrySet().stream()
                    .filter(entry -> entry.getValue() == incoming)
                    .map(Map.Entry::getKey).findFirst().orElse(null);

            if (userId != null) {
                // 已认证用户
                if (actualMsg.getType() == Message.MessageType.TEXT_MESSAGE_ENCRYPTED) {

                    // 精准转发加密消息
                    TextMessage textMsg = (TextMessage) actualMsg;
                    String targetId = textMsg.getTargetUserId();

                    Channel targetChannel = LOGGED_IN_USERS.get(targetId);

                    if (targetChannel != null) {
                        targetChannel.writeAndFlush(actualMsg);
                        System.out.println("[转发日志] 用户 " + userId + " 发送加密消息给用户 " + targetId + "。");
                    } else {
                        LoginResponse errorResponse = new LoginResponse("SERVER", false,
                                "消息发送失败：目标用户 [" + targetId + "] 当前不在线。");
                        ctx.channel().writeAndFlush(errorResponse);
                        System.err.println("[系统日志] 用户 " + userId + " 尝试向离线用户 " + targetId + " 发送消息失败。");
                    }

                } else {
                    System.out.println("[系统日志] 收到来自 " + userId + " 的非聊天消息: " + actualMsg.getType());
                }
            } else {
                // 拒绝未认证用户的任何消息
                incoming.writeAndFlush(new LoginResponse("SERVER", false, "请先登录！"));
                System.err.println("[安全警告] 未认证连接 " + incoming.remoteAddress() + " 尝试发送消息。");
            }
        }
    }

    /**
     * 处理登录请求的私有方法 (参数改为 Message 基类，内部转换)
     */
    private void handleLoginRequest(ChannelHandlerContext ctx, Message msg) {
        if (!(msg instanceof LoginRequest)) return; // 类型检查
        LoginRequest request = (LoginRequest) msg; // 安全转换

        String userId = request.getSenderId();
        String password = request.getPassword();
        String publicKey = request.getPublicKey();
        Channel incoming = ctx.channel();

        LoginResponse response;

        if (LOGGED_IN_USERS.containsKey(userId)) {
            response = new LoginResponse(userId, false, "用户已在线，请勿重复登录。");
        } else {
            User user = DatabaseManager.getUser(userId);

            if (user != null && user.getPasswordHash().equals(password)) {
                // 认证成功
                LOGGED_IN_USERS.put(userId, incoming);
                response = new LoginResponse(userId, true, "登录成功！");

                // 关键操作：更新公钥
                DatabaseManager.updatePublicKey(userId, publicKey);

                // 广播通知新人上线
                sendSystemMessageToAll(incoming, new TextMessage("SYSTEM", userId + " 加入了聊天室。"));

                System.out.println("[认证成功] 用户 " + userId + " 登录，公钥已存储。在线: " + LOGGED_IN_USERS.size());
            } else {
                // 自动注册逻辑
                if (DatabaseManager.registerUser(userId, password)) {
                    LOGGED_IN_USERS.put(userId, incoming);
                    response = new LoginResponse(userId, true, "注册并登录成功！");

                    // 关键操作：存储公钥
                    DatabaseManager.updatePublicKey(userId, publicKey);

                    // 广播通知新人上线
                    sendSystemMessageToAll(incoming, new TextMessage("SYSTEM", userId + " 首次加入聊天室。"));

                    System.out.println("[注册成功] 用户 " + userId + " 已注册并登录，公钥已存储。");
                } else {
                    // 认证失败
                    response = new LoginResponse(userId, false, "用户名或密码错误。");
                    System.err.println("[认证失败] 用户 " + userId + " 尝试登录失败。");
                }
            }
        }

        incoming.writeAndFlush(response);
    }

    /**
     * 处理密钥交换请求 (参数改为 Message 基类，内部转换)
     */
    private void handleKeyExchangeRequest(ChannelHandlerContext ctx, Message msg) {
        if (!(msg instanceof KeyExchangeRequest)) return; // 类型检查
        KeyExchangeRequest request = (KeyExchangeRequest) msg; // 安全转换

        String senderId = request.getSenderId();
        String targetId = request.getTargetUserId();
        Channel incoming = ctx.channel();

        KeyExchangeResponse response;

        // 安全检查：如果发送方未登录，拒绝
        if (!LOGGED_IN_USERS.containsKey(senderId)) {
            response = new KeyExchangeResponse("SERVER", false, "请先登录！", targetId, null);
        } else {
            User targetUser = DatabaseManager.getUser(targetId);

            if (targetUser == null || targetUser.getPublicKey() == null || targetUser.getPublicKey().isEmpty()) {
                // 目标用户不存在或未上传公钥
                response = new KeyExchangeResponse("SERVER", false, "目标用户未找到或公钥不可用。", targetId, null);
                System.err.println("[安全警告] 用户 " + senderId + " 请求用户 " + targetId + " 公钥失败。");
            } else {
                // 成功返回目标公钥
                response = new KeyExchangeResponse(senderId, true, "公钥获取成功。", targetId, targetUser.getPublicKey());
                System.out.println("[系统日志] 用户 " + senderId + " 成功获取用户 " + targetId + " 的公钥。");
            }
        }

        incoming.writeAndFlush(response);
    }

    /**
     * 处理和转发 AES 密钥交换消息 (参数改为 Message 基类，内部转换)
     */
    private void handleAESKeyExchange(ChannelHandlerContext ctx, Message msg) {
        if (!(msg instanceof AESKeyExchangeMessage)) return; // 类型检查
        AESKeyExchangeMessage exchangeMsg = (AESKeyExchangeMessage) msg; // 安全转换

        String senderId = exchangeMsg.getSenderId();
        String targetId = exchangeMsg.getTargetUserId();

        Channel targetChannel = LOGGED_IN_USERS.get(targetId);

        if (targetChannel != null) {
            // 目标用户在线，直接转发给目标用户的 Channel
            targetChannel.writeAndFlush(exchangeMsg);
            System.out.println("[转发日志] 用户 " + senderId + " 成功转发 AES 密钥给用户 " + targetId + "。");
        } else {
            // 目标用户不在线
            LoginResponse errorResponse = new LoginResponse("SERVER", false,
                    "密钥交换失败：目标用户 [" + targetId + "] 当前不在线。");
            ctx.channel().writeAndFlush(errorResponse);
            System.err.println("[系统日志] 用户 " + senderId + " 尝试与离线用户 " + targetId + " 交换密钥失败。");
        }
    }

    // 3. 连接断开
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        Channel incoming = ctx.channel();
        CHANNELS.remove(incoming);

        String disconnectedUserId = LOGGED_IN_USERS.entrySet().stream()
                .filter(entry -> entry.getValue() == incoming)
                .map(Map.Entry::getKey).findFirst().orElse(null);

        if (disconnectedUserId != null) {
            LOGGED_IN_USERS.remove(disconnectedUserId);

            // 广播通知离线
            sendSystemMessageToAll(incoming, new TextMessage("SYSTEM", disconnectedUserId + " 离开了聊天室。"));

            System.out.println("[系统日志] 用户 " + disconnectedUserId + " 断开连接。在线: " + LOGGED_IN_USERS.size());
        } else {
            System.out.println("[系统日志] 未认证连接 " + incoming.remoteAddress() + " 断开。");
        }
    }

    /**
     * 用于发送系统消息给所有已登录用户
     */
    private void sendSystemMessageToAll(Channel excludeChannel, Message message) {
        for (Channel channel : CHANNELS) {
            // 只有已登录的用户才接收系统消息
            if (LOGGED_IN_USERS.containsValue(channel) && channel != excludeChannel) {
                channel.writeAndFlush(message);
            }
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        System.err.println("发生异常，连接关闭: " + cause.getMessage());
        // 建议在调试阶段打印堆栈以获取更详细信息
        // cause.printStackTrace();
        ctx.close();
    }
}