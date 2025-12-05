package com.my.chatroom;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 压力测试启动器
 * 作用：模拟大量用户并发连接，测试服务器负载能力。
 */
public class StressTest {

    // 设定模拟的客户端数量
    private static final int CLIENT_COUNT = 1000;
    // 发送消息的间隔 (毫秒)
    private static final int MSG_INTERVAL = 5000;

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 8888;

    private static final AtomicInteger successCount = new AtomicInteger(0);
    private static final AtomicInteger failCount = new AtomicInteger(0);

    public static void main(String[] args) {
        System.out.println("🚀 开始压力测试，目标: " + CLIENT_COUNT + " 个并发用户...");

        // 使用线程池模拟用户
        ExecutorService executor = Executors.newFixedThreadPool(50); // 线程池大小控制连接速率

        for (int i = 0; i < CLIENT_COUNT; i++) {
            final int index = i;
            executor.submit(() -> startBotUser(index));
            try {
                // 稍微错开连接时间，避免瞬间把本机端口耗尽
                Thread.sleep(20);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private static void startBotUser(int index) {
        String userId = "Bot_" + index + "_" + UUID.randomUUID().toString().substring(0, 4);
        Client botClient = new Client();

        try {
            // 1. 连接 (不使用 Platform.runLater，传入简单的回调)
            botClient.connect(HOST, PORT,
                    (loginResp) -> {
                        if (loginResp.isSuccess()) {
                            successCount.incrementAndGet();
                            System.out.println("✅ [" + userId + "] 登录成功 (在线: " + successCount.get() + ")");
                            // 登录成功后，开启定时发送消息循环
                            startSpamming(botClient, userId);
                        } else {
                            System.err.println("❌ [" + userId + "] 登录被拒: " + loginResp.getMessage());
                            failCount.incrementAndGet();
                            botClient.disconnect();
                        }
                    },
                    (msg) -> {
                        // 收到消息的回调，压测时通常忽略，或者只打印统计信息
                        // System.out.println("[" + userId + "] 收到: " + msg.getType());
                    }
            );

            // 2. 发送登录请求 (公钥随便发一个占位，压测不测E2EE握手)
            // 注意：这里需要模拟 LoginController 里的逻辑
            String fakePublicKey = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQE...";
            LoginRequest loginReq = new LoginRequest(userId, "password123", fakePublicKey);
            botClient.sendMessage(loginReq);

        } catch (Exception e) {
            System.err.println("💥 [" + userId + "] 连接异常: " + e.getMessage());
            failCount.incrementAndGet();
        }
    }

    private static void startSpamming(Client client, String userId) {
        new Thread(() -> {
            try {
                while (true) {
                    Thread.sleep(MSG_INTERVAL + (int)(Math.random() * 2000));

                    // 发送群聊消息
                    TextMessage msg = new TextMessage(userId, "我是机器人 " + userId + "，现在的性能还好吗？");
                    msg.setTargetUserId("ALL");
                    client.sendMessage(msg);
                }
            } catch (Exception e) {
                // 连接断开则退出循环
            }
        }).start();
    }
}