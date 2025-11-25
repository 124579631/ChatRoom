package com.my.chatroom;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.animation.PauseTransition;
import javafx.util.Duration;

import javax.crypto.SecretKey;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ChatController {

    @FXML private ListView<String> userListView;
    @FXML private ListView<HBox> chatListView; // 聊天列表
    @FXML private TextField messageInputField;
    @FXML private Button sendButton;
    @FXML private Button burnButton;
    @FXML private Text chatTargetLabel;
    @FXML private Button connectButton;

    private Client nettyClient;
    private String currentUserId;
    private String currentChatTarget;
    private final ObservableList<String> onlineUsers = FXCollections.observableArrayList();
    private static final SimpleDateFormat SDF = new SimpleDateFormat("HH:mm:ss");

    private final List<String[]> activeGroupMessages = new ArrayList<>();
    private final Set<String> unreadSenders = new HashSet<>();
    private final Map<String, List<String>> pendingBurnMessages = new ConcurrentHashMap<>();

    @FXML
    public void initialize() {
        userListView.setItems(onlineUsers);
        chatTargetLabel.setText("[请选择用户]");

        // --- 【修复 1】禁用聊天列表的“选中”功能，防止点击变色/消失 ---
        chatListView.setFocusTraversable(false);
        chatListView.setSelectionModel(new NoSelectionModel<>()); // 使用自定义的不选中模型

        // --- 【修复 2】强制设置 Cell 样式，不依赖外部 CSS ---
        chatListView.setCellFactory(lv -> new ListCell<HBox>() {
            @Override
            protected void updateItem(HBox item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    setText(null);
                    // 强制透明背景
                    setStyle("-fx-background-color: transparent; -fx-padding: 0;");
                } else {
                    setGraphic(item);
                    setText(null);
                    // 强制透明背景，带有内边距
                    setStyle("-fx-background-color: transparent; -fx-padding: 5px;");
                }
            }
        });

        // 左侧用户列表样式 (内联样式，兜底防止 CSS 失败)
        userListView.setCellFactory(lv -> new ListCell<String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                // 默认样式：深灰底绿字
                String baseStyle = "-fx-background-color: #2a2a2a; -fx-text-fill: #00ff00; -fx-border-color: #444444; -fx-border-width: 0 0 1 0;";

                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    setStyle("-fx-background-color: #2a2a2a; -fx-border-width: 0;");
                } else {
                    String rawId = item.replace(" (我)", "").replace(" (🔴 新消息)", "");
                    if (unreadSenders.contains(rawId)) {
                        setText(item + " (🔴 新消息)");
                        // 红底白字
                        setStyle("-fx-background-color: #880000; -fx-text-fill: white; -fx-font-weight: bold;");
                    } else if (isSelected()) {
                        setText(item);
                        // 选中：绿底黑字
                        setStyle("-fx-background-color: #00ff00; -fx-text-fill: black; -fx-font-weight: bold;");
                    } else {
                        setText(item);
                        setStyle(baseStyle);
                    }
                }
            }
        });

        // 左侧列表点击监听
        userListView.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldValue, newValue) -> {
                    if (newValue != null) {
                        String realTargetId = newValue.replace(" (我)", "").replace(" (🔴 新消息)", "");
                        if (!realTargetId.equals(currentChatTarget)) {
                            switchChatTarget(realTargetId);
                        }
                    }
                }
        );

        appendLogMessage("系统就绪 - 样式强制修复版");
    }

    // --- 内部类：禁止选中的 SelectionModel ---
    private static class NoSelectionModel<T> extends MultipleSelectionModel<T> {
        @Override public ObservableList<Integer> getSelectedIndices() { return FXCollections.emptyObservableList(); }
        @Override public ObservableList<T> getSelectedItems() { return FXCollections.emptyObservableList(); }
        @Override public void selectIndices(int index, int... indices) {}
        @Override public void selectAll() {}
        @Override public void selectFirst() {}
        @Override public void selectLast() {}
        @Override public void clearAndSelect(int index) {}
        @Override public void select(int index) {}
        @Override public void select(T obj) {}
        @Override public void clearSelection(int index) {}
        @Override public void clearSelection() {}
        @Override public boolean isSelected(int index) { return false; }
        @Override public boolean isEmpty() { return true; }
        @Override public void selectPrevious() {}
        @Override public void selectNext() {}
    }

    @FXML
    public void handleJoinPublicChat() {
        userListView.getSelectionModel().clearSelection();
        switchChatTarget("ALL");
        chatTargetLabel.setText("📢 公共安全群聊");
        connectButton.setVisible(false);
        refreshGroupChatView();
    }

    @FXML
    public void handleConnectAction() {
        if (currentChatTarget == null || "ALL".equals(currentChatTarget)) return;
        if (nettyClient.getSharedAesKey(currentChatTarget) != null) {
            appendLogMessage("✅ 通道已存在");
            return;
        }
        appendLogMessage("🔄 请求密钥交换...");
        KeyExchangeRequest request = new KeyExchangeRequest(currentUserId, currentChatTarget);
        nettyClient.sendMessage(request);
    }

    @FXML
    private void handleSendMessageAction() {
        sendMsg(false);
    }

    @FXML
    private void handleBurnMessageAction() {
        sendMsg(true);
    }

    private void sendMsg(boolean isBurn) {
        String messageContent = messageInputField.getText();
        if (messageContent.isEmpty() || currentChatTarget == null) return;

        if ("ALL".equals(currentChatTarget)) {
            TextMessage groupMsg = new TextMessage(currentUserId, messageContent);
            groupMsg.setTargetUserId("ALL");
            nettyClient.sendMessage(groupMsg);
            messageInputField.clear();
            return;
        }

        SecretKey sharedKey = nettyClient.getSharedAesKey(currentChatTarget);
        if (sharedKey == null) {
            appendLogMessage("⚠️ 未建立加密通道，无法发送。");
            return;
        }

        try {
            String encryptedContent = EncryptionUtils.aesEncrypt(messageContent, sharedKey);

            if (isBurn) {
                BurnAfterReadMessage barMsg = new BurnAfterReadMessage(currentUserId, encryptedContent);
                barMsg.setTargetUserId(currentChatTarget);
                nettyClient.sendMessage(barMsg);
                appendChatMessage(currentUserId, "(✳) " + messageContent);
            } else {
                TextMessage textMsg = new TextMessage(currentUserId, encryptedContent);
                textMsg.setTargetUserId(currentChatTarget);
                nettyClient.sendMessage(textMsg);
                DatabaseManager.saveEncryptedMessage(currentUserId, currentChatTarget, true, encryptedContent);
                appendChatMessage(currentUserId, messageContent);
            }
            messageInputField.clear();
        } catch (Exception e) {
            appendLogMessage("❌ 发送失败: " + e.getMessage());
        }
    }

    public void setClient(Client client, String userId) {
        this.nettyClient = client;
        this.currentUserId = userId;
        client.setMessageCallback(this::handleIncomingMessage);
    }

    private void handleIncomingMessage(Message message) {
        if (message instanceof UserListMessage) {
            UserListMessage userList = (UserListMessage) message;
            Platform.runLater(() -> {
                onlineUsers.clear();
                if (userList.getOnlineUsers().contains(currentUserId)) {
                    onlineUsers.add(currentUserId + " (我)");
                }
                userList.getOnlineUsers().stream()
                        .filter(user -> !user.equals(currentUserId))
                        .sorted()
                        .forEach(onlineUsers::add);
            });
            return;
        }

        if (message instanceof TextMessage) {
            TextMessage textMsg = (TextMessage) message;
            String senderId = textMsg.getSenderId();
            String targetId = textMsg.getTargetUserId();

            if ("ALL".equals(targetId)) {
                Platform.runLater(() -> {
                    String time = SDF.format(new Timestamp(System.currentTimeMillis()));
                    String[] msgData = new String[]{senderId, textMsg.getContent(), time};
                    activeGroupMessages.add(msgData);

                    if ("ALL".equals(currentChatTarget)) refreshGroupChatView();

                    PauseTransition pause = new PauseTransition(Duration.seconds(10));
                    pause.setOnFinished(e -> {
                        activeGroupMessages.remove(msgData);
                        if ("ALL".equals(currentChatTarget)) refreshGroupChatView();
                    });
                    pause.play();
                });
                return;
            }

            if (senderId.equals("SYSTEM")) {
                if (currentChatTarget != null) appendLogMessage(textMsg.getContent());
                return;
            }

            DatabaseManager.saveEncryptedMessage(currentUserId, senderId, false, textMsg.getContent());

            if (!senderId.equals(currentChatTarget)) {
                Platform.runLater(() -> {
                    unreadSenders.add(senderId);
                    userListView.refresh();
                });
                return;
            }

            String decryptedContent = decryptMessage(senderId, textMsg.getContent());
            appendChatMessage(senderId, decryptedContent);
            return;
        }

        if (message instanceof BurnAfterReadMessage) {
            BurnAfterReadMessage burnMsg = (BurnAfterReadMessage) message;
            String senderId = burnMsg.getSenderId();

            if (!senderId.equals(currentChatTarget)) {
                Platform.runLater(() -> {
                    unreadSenders.add(senderId);
                    userListView.refresh();
                    pendingBurnMessages.computeIfAbsent(senderId, k -> new ArrayList<>()).add(burnMsg.getEncryptedContent());
                });
                return;
            }
            displayBurnMessage(senderId, burnMsg.getEncryptedContent());
        }
    }

    private void refreshGroupChatView() {
        if (!"ALL".equals(currentChatTarget)) return;

        List<HBox> newItems = new ArrayList<>();
        newItems.add(createSystemBubble("📢 公共群聊频道 (消息10秒后销毁)"));

        List<String[]> snapshot = new ArrayList<>(activeGroupMessages);
        for (String[] msgData : snapshot) {
            String sender = msgData[0];
            String content = msgData[1];
            HBox bubble = createChatBubble(sender, content);
            newItems.add(bubble);
        }

        Platform.runLater(() -> {
            chatListView.getItems().setAll(newItems);
            if (!newItems.isEmpty()) {
                chatListView.scrollTo(newItems.size() - 1);
            }
        });
    }

    private void switchChatTarget(String targetId) {
        this.currentChatTarget = targetId;
        if (unreadSenders.contains(targetId)) {
            unreadSenders.remove(targetId);
            userListView.refresh();
        }

        if ("ALL".equals(targetId)) {
            connectButton.setVisible(false);
            refreshGroupChatView();
            return;
        }

        connectButton.setVisible(true);
        chatTargetLabel.setText("正在与 " + targetId + " 聊天");

        chatListView.getItems().clear();

        SecretKey sharedKey = nettyClient.getSharedAesKey(targetId);
        if (sharedKey == null) {
            appendLogMessage("未建立加密通道");
            appendLogMessage("请点击上方 [🔐 建立加密通道] 按钮");
        } else {
            List<String[]> history = DatabaseManager.getEncryptedHistory(currentUserId, targetId);
            if (!history.isEmpty()) {
                appendLogMessage("--- 加载本地历史 ---");
                for (String[] record : history) {
                    try {
                        String decryptedContent = EncryptionUtils.aesDecrypt(record[1], sharedKey);
                        String sender = record[0].equals("1") ? currentUserId : targetId;
                        appendChatMessage(sender, decryptedContent);
                    } catch (Exception e) { }
                }
            }
        }

        if (pendingBurnMessages.containsKey(targetId)) {
            List<String> burns = pendingBurnMessages.remove(targetId);
            if (burns != null && !burns.isEmpty()) {
                appendLogMessage("🔥 收到 " + burns.size() + " 条新的阅后即焚消息");
                for (String encryptedContent : burns) {
                    displayBurnMessage(targetId, encryptedContent);
                }
            }
        }
    }

    private void displayBurnMessage(String senderId, String encryptedContent) {
        String decryptedContent = decryptMessage(senderId, encryptedContent);
        HBox bubbleBox = appendChatMessage(senderId, "(✳) " + decryptedContent);

        PauseTransition pause = new PauseTransition(Duration.seconds(10));
        pause.setOnFinished(e -> {
            if (currentChatTarget != null && currentChatTarget.equals(senderId)) {
                Platform.runLater(() -> {
                    chatListView.getItems().remove(bubbleBox);
                    appendLogMessage("一条阅后即焚消息已销毁");
                });
            }
        });
        pause.play();
    }

    /**
     * 【核心修复 3】直接在代码里写死气泡样式 (Style)，不再依赖外部 CSS
     */
    private HBox createChatBubble(String sender, String message) {
        boolean isMe = sender.equals(currentUserId);

        // 1. 用户名标签
        Label nameLabel = new Label(sender);
        // 灰色小字
        nameLabel.setStyle("-fx-text-fill: #888888; -fx-font-size: 10px; -fx-padding: 0 0 2px 0;");

        // 2. 消息气泡 (Label)
        Label msgLabel = new Label(message);
        msgLabel.setWrapText(true);
        // 限制最大宽度
        msgLabel.setMaxWidth(400);

        // --- 强制样式定义 ---
        String commonStyle = "-fx-font-size: 14px; -fx-padding: 8px 12px; -fx-background-radius: 10px; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.3), 3, 0, 1, 1);";

        if (isMe) {
            // 我的消息：亮绿色背景，黑字
            msgLabel.setStyle(commonStyle + "-fx-background-color: #00ff00; -fx-text-fill: black; -fx-font-weight: bold; -fx-background-radius: 10px 0 10px 10px;");
        } else {
            // 对方消息：深灰色背景，白字
            msgLabel.setStyle(commonStyle + "-fx-background-color: #444444; -fx-text-fill: white; -fx-background-radius: 0 10px 10px 10px;");
        }

        // 3. 垂直布局：名字在上，气泡在下
        VBox vBox = new VBox(2, nameLabel, msgLabel);
        vBox.setAlignment(isMe ? Pos.TOP_RIGHT : Pos.TOP_LEFT);

        // 4. 水平容器 HBox
        HBox container = new HBox(vBox);
        container.setAlignment(isMe ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);

        return container;
    }

    private HBox createSystemBubble(String message) {
        Label logLabel = new Label(message);
        // 系统消息：浅灰色胶囊状背景
        logLabel.setStyle("-fx-background-color: rgba(200, 200, 200, 0.2); -fx-text-fill: #888888; -fx-font-size: 12px; -fx-padding: 4px 10px; -fx-background-radius: 15px;");
        HBox container = new HBox(logLabel);
        container.setAlignment(Pos.CENTER);
        return container;
    }

    private HBox appendChatMessage(String sender, String message) {
        HBox bubble = createChatBubble(sender, message);
        Platform.runLater(() -> {
            chatListView.getItems().add(bubble);
            chatListView.scrollTo(chatListView.getItems().size() - 1);
        });
        return bubble;
    }

    private void appendLogMessage(String message) {
        HBox bubble = createSystemBubble(message);
        Platform.runLater(() -> {
            chatListView.getItems().add(bubble);
            chatListView.scrollTo(chatListView.getItems().size() - 1);
        });
    }

    private String decryptMessage(String senderId, String encryptedContent) {
        SecretKey key = nettyClient.getSharedAesKey(senderId);
        if (key != null) {
            try { return EncryptionUtils.aesDecrypt(encryptedContent, key); }
            catch (Exception e) { return "[解密失败]"; }
        }
        return "[无密钥]";
    }
}