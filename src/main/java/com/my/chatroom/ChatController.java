package com.my.chatroom;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.text.Text;

import javax.crypto.SecretKey;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.List;

/**
 * 主聊天界面的控制器 (ChatController)
 * 作用：处理 Chat.fxml 中的所有 UI 交互和消息显示。
 */
public class ChatController {

    // FXML 控件注入
    @FXML private ListView<String> userListView;
    @FXML private TextArea chatHistoryArea;
    @FXML private TextField messageInputField;
    @FXML private ToggleButton burnModeToggle;
    @FXML private Button sendButton;
    @FXML private Text chatTargetLabel;

    // 内部状态
    private Client nettyClient;
    private String currentUserId;
    private String currentChatTarget;
    private final ObservableList<String> onlineUsers = FXCollections.observableArrayList();
    private static final SimpleDateFormat SDF = new SimpleDateFormat("HH:mm:ss");

    /**
     * FXML 初始化时调用
     */
    @FXML
    public void initialize() {
        // 绑定 ListView 到在线用户列表
        userListView.setItems(onlineUsers);
        chatHistoryArea.setText("--- [安全通信已初始化] ---\n");
        chatTargetLabel.setText("[请选择用户]");

        // 【核心】添加 ListView 监听器，用于切换聊天对象
        userListView.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldValue, newValue) -> {
                    if (newValue != null && !newValue.equals(currentChatTarget)) {
                        // 当用户点击了新的目标
                        switchChatTarget(newValue);
                    }
                }
        );


    }

    /**
     * 【核心】从 LoginController 注入 Netty 客户端实例和用户ID
     */
    public void setClient(Client client, String userId) {
        this.nettyClient = client;
        this.currentUserId = userId;

        // 【核心】重新设置 Netty 客户端的消息回调，让消息流向这个 Controller
        // (注意：LoginController 的回调将不再被使用)
        client.setMessageCallback(this::handleIncomingMessage);
    }

    /**
     * 【核心】处理所有从服务器传入的消息 (在 UI 线程)
     */
    private void handleIncomingMessage(Message message) {
        // 1. 更新用户列表
        if (message instanceof UserListMessage) {
            UserListMessage userList = (UserListMessage) message;
            Platform.runLater(() -> {
                onlineUsers.clear();
                // 不显示自己
                userList.getOnlineUsers().stream()
                        .filter(user -> !user.equals(currentUserId))
                        .forEach(onlineUsers::add);
            });
            return;
        }

        // 2. 处理加密的普通消息
        if (message instanceof TextMessage) {
            TextMessage textMsg = (TextMessage) message;
            String senderId = textMsg.getSenderId();

            // 如果是系统广播
            if (senderId.equals("SYSTEM")) {
                appendLogMessage(textMsg.getContent());
                return;
            }

            // 解密消息
            String decryptedContent = decryptMessage(senderId, textMsg.getContent());
            appendChatMessage(senderId, decryptedContent);

            // 【核心】接收方将加密消息保存到本地历史记录
            DatabaseManager.saveEncryptedMessage(currentUserId, senderId, false, textMsg.getContent());
            return;
        }

        // 3. 处理阅后即焚消息
        if (message instanceof BurnAfterReadMessage) {
            BurnAfterReadMessage burnMsg = (BurnAfterReadMessage) message;
            String senderId = burnMsg.getSenderId();

            String decryptedContent = decryptMessage(senderId, burnMsg.getEncryptedContent());
            appendChatMessage(senderId, decryptedContent + " (阅后即焚)");

            // 阅后即焚消息不保存到历史记录
        }
    }

    /**
     * 【核心】处理发送按钮点击事件
     */
    @FXML
    private void handleSendMessageAction() {
        String messageContent = messageInputField.getText();
        if (messageContent.isEmpty() || currentChatTarget == null) {
            return; // 不发送空消息或未选择目标
        }

        // 1. 检查 E2EE 会话是否已建立
        SecretKey sharedKey = nettyClient.getSharedAesKey(currentChatTarget);
        if (sharedKey == null) {
            // 自动发起密钥交换
            appendLogMessage("⚠️ 正在与 " + currentChatTarget + " 建立安全会Row (请求公钥)...");
            KeyExchangeRequest request = new KeyExchangeRequest(currentUserId, currentChatTarget);
            nettyClient.sendMessage(request);

            // (消息会在 E2EE 建立后由用户手动重发，或您可以实现一个消息队列)
            appendLogMessage("请在会话建立后重试发送。");
            return;
        }

        try {
            // 2. 加密消息
            String encryptedContent = EncryptionUtils.aesEncrypt(messageContent, sharedKey);

            // 3. 根据 ToggleButton 决定发送哪种消息
            if (burnModeToggle.isSelected()) {
                // 发送阅后即焚
                BurnAfterReadMessage barMsg = new BurnAfterReadMessage(currentUserId, encryptedContent);
                barMsg.setTargetUserId(currentChatTarget);
                nettyClient.sendMessage(barMsg);

                // 发送方也不保存
                appendChatMessage(currentUserId, messageContent + " (阅后即焚)");

            } else {
                // 发送普通消息
                TextMessage textMsg = new TextMessage(currentUserId, encryptedContent);
                textMsg.setTargetUserId(currentChatTarget);
                nettyClient.sendMessage(textMsg);

                // 发送方保存到本地
                DatabaseManager.saveEncryptedMessage(currentUserId, currentChatTarget, true, encryptedContent);
                appendChatMessage(currentUserId, messageContent);
            }

            messageInputField.clear();

        } catch (Exception e) {
            appendLogMessage("❌ 加密或发送失败: " + e.getMessage());
        }
    }

    // --- 辅助方法 ---

    /**
     * 切换聊天对象
     */
    private void switchChatTarget(String targetId) {
        this.currentChatTarget = targetId;
        chatTargetLabel.setText("正在与 " + targetId + " 聊天");
        chatHistoryArea.clear(); // 清空旧聊天记录

        // 加载本地加密历史记录
        SecretKey sharedKey = nettyClient.getSharedAesKey(targetId);
        if (sharedKey == null) {
            appendLogMessage("--- [未建立安全会话，无法解密历史记录] ---");
            return;
        }

        appendLogMessage("--- [正在加载本地加密历史] ---");
        List<String[]> history = DatabaseManager.getEncryptedHistory(currentUserId, targetId);
        for (String[] record : history) {
            String isSender = record[0];
            String encryptedContent = record[1];
            String timestamp = record[2]; // (暂时不用)

            try {
                String decryptedContent = EncryptionUtils.aesDecrypt(encryptedContent, sharedKey);
                String sender = isSender.equals("1") ? currentUserId : targetId;
                appendChatMessage(sender, decryptedContent);
            } catch (Exception e) {
                appendLogMessage("[历史记录解密失败]");
            }
        }
        appendLogMessage("--- [历史记录加载完毕] ---");
    }

    /**
     * 尝试解密消息
     */
    private String decryptMessage(String senderId, String encryptedContent) {
        SecretKey key = nettyClient.getSharedAesKey(senderId);
        if (key != null) {
            try {
                return EncryptionUtils.aesDecrypt(encryptedContent, key);
            } catch (Exception e) {
                return "[解密失败]";
            }
        }
        return "[无共享密钥，无法解密]";
    }

    // 在 UI 线程安全地追加聊天消息
    private void appendChatMessage(String sender, String message) {
        Platform.runLater(() -> {
            String time = SDF.format(new Timestamp(System.currentTimeMillis()));
            String senderDisplay = sender.equals(currentUserId) ? "我" : sender;

            chatHistoryArea.appendText(String.format("[%s] %s: %s\n", time, senderDisplay, message));
        });
    }

    // 在 UI 线程安全地追加系统日志
    private void appendLogMessage(String message) {
        Platform.runLater(() -> {
            chatHistoryArea.appendText(String.format("--- %s ---\n", message));
        });
    }

    // (当窗口关闭时，应调用 nettyClient.disconnect())
}