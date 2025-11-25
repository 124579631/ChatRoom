package com.my.chatroom;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.animation.PauseTransition;
import javafx.util.Duration;

import javax.crypto.SecretKey;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 主聊天界面控制器 - 最终完美版
 * 修复：图片按钮乱码、发送顺序、历史记录图片显示、接收提示
 */
public class ChatController {

    @FXML private ListView<String> userListView;
    @FXML private ListView<HBox> chatListView;
    @FXML private TextField messageInputField;
    @FXML private Button sendButton;
    @FXML private Button burnButton;
    @FXML private Text chatTargetLabel;
    @FXML private Button connectButton;

    // 暂存区
    @FXML private ScrollPane pendingFileScroll;
    @FXML private HBox pendingFileBox;

    private Client nettyClient;
    private String currentUserId;
    private String currentChatTarget;
    private final ObservableList<String> onlineUsers = FXCollections.observableArrayList();
    private static final SimpleDateFormat SDF = new SimpleDateFormat("HH:mm:ss");

    private final List<String[]> activeGroupMessages = new ArrayList<>();
    private boolean showGroupHeader = false;
    private final Set<String> unreadSenders = new HashSet<>();
    private final Map<String, List<String>> pendingBurnMessages = new ConcurrentHashMap<>();
    private final List<File> pendingFiles = new ArrayList<>();

    // 【核心】定义图片在数据库存储时的前缀协议
    private static final String IMG_PREFIX = "::IMG::";

    @FXML
    public void initialize() {
        userListView.setItems(onlineUsers);
        chatTargetLabel.setText("[请选择用户]");

        chatListView.setFocusTraversable(false);
        chatListView.setSelectionModel(new NoSelectionModel<>());

        chatListView.setCellFactory(lv -> new ListCell<HBox>() {
            @Override
            protected void updateItem(HBox item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    setText(null);
                    setStyle("-fx-background-color: transparent; -fx-padding: 0;");
                } else {
                    setGraphic(item);
                    setText(null);
                    setStyle("-fx-background-color: transparent; -fx-padding: 5px;");
                }
            }
        });

        userListView.setCellFactory(lv -> new ListCell<String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                String baseStyle = "-fx-background-color: #2a2a2a; -fx-text-fill: #00ff00; -fx-border-color: #444444; -fx-border-width: 0 0 1 0;";
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    setStyle("-fx-background-color: #2a2a2a; -fx-border-width: 0;");
                } else {
                    String rawId = item.replace(" (我)", "").replace(" (🔴 新消息)", "");
                    if (unreadSenders.contains(rawId)) {
                        setText(item + " (🔴 新消息)");
                        setStyle("-fx-background-color: #880000; -fx-text-fill: white; -fx-font-weight: bold;");
                    } else if (isSelected()) {
                        setText(item);
                        setStyle("-fx-background-color: #00ff00; -fx-text-fill: black; -fx-font-weight: bold;");
                    } else {
                        setText(item);
                        setStyle(baseStyle);
                    }
                }
            }
        });

        userListView.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
            if (newV != null) {
                String realTargetId = newV.replace(" (我)", "").replace(" (🔴 新消息)", "");
                if (!realTargetId.equals(currentChatTarget)) switchChatTarget(realTargetId);
            }
        });

        appendLogMessage("系统就绪");
    }

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

    // ================= 交互逻辑 =================

    @FXML
    public void handleJoinPublicChat() {
        userListView.getSelectionModel().clearSelection();
        switchChatTarget("ALL");
        chatTargetLabel.setText("📢 公共安全群聊");
        connectButton.setVisible(false);
        this.showGroupHeader = true;
        refreshGroupChatView();

        PauseTransition pause = new PauseTransition(Duration.seconds(10));
        pause.setOnFinished(e -> {
            this.showGroupHeader = false;
            if ("ALL".equals(currentChatTarget)) refreshGroupChatView();
        });
        pause.play();
    }

    @FXML
    public void handleConnectAction() {
        if (currentChatTarget == null || "ALL".equals(currentChatTarget)) return;
        if (nettyClient.getSharedAesKey(currentChatTarget) != null) {
            appendLogMessage("✅ 通道已存在");
            return;
        }
        appendLogMessage("🔄 请求密钥交换...");
        nettyClient.sendMessage(new KeyExchangeRequest(currentUserId, currentChatTarget));
    }

    @FXML
    public void handleSelectFileAction() {
        if (currentChatTarget == null) {
            appendLogMessage("❌ 请先选择聊天对象");
            return;
        }
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("选择图片");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("图片文件", "*.png", "*.jpg", "*.jpeg", "*.gif"));
        List<File> files = fileChooser.showOpenMultipleDialog(sendButton.getScene().getWindow());

        if (files != null) {
            for (File file : files) {
                if (file.length() > 2 * 1024 * 1024) {
                    appendLogMessage("❌ 忽略过大文件: " + file.getName());
                    continue;
                }
                pendingFiles.add(file);
                addFilePreview(file);
            }
            updatePendingAreaVisibility();
        }
    }

    private void addFilePreview(File file) {
        try {
            Image thumb = new Image(file.toURI().toString(), 50, 50, true, true);
            ImageView iv = new ImageView(thumb);
            Button removeBtn = new Button("x");
            removeBtn.setStyle("-fx-background-color: red; -fx-text-fill: white; -fx-font-size: 8px; -fx-padding: 0 4px;");
            removeBtn.setOnAction(e -> {
                pendingFiles.remove(file);
                updatePendingAreaVisibility();
                pendingFileBox.getChildren().clear();
                for (File f : pendingFiles) addFilePreview(f);
            });
            VBox container = new VBox(2, iv, removeBtn);
            container.setAlignment(Pos.CENTER);
            pendingFileBox.getChildren().add(container);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void updatePendingAreaVisibility() {
        boolean hasFiles = !pendingFiles.isEmpty();
        pendingFileScroll.setVisible(hasFiles);
        pendingFileScroll.setMaxHeight(hasFiles ? 80 : 0);
    }

    @FXML
    private void handleSendMessageAction() {
        sendMsg(false);
    }

    @FXML
    private void handleBurnMessageAction() {
        sendMsg(true);
    }

    // --- 【修改】优化发送逻辑：先发图片，再发文字，且处理存库 ---
    private void sendMsg(boolean isBurn) {
        String messageContent = messageInputField.getText();
        boolean hasText = !messageContent.isEmpty();
        boolean hasFiles = !pendingFiles.isEmpty();

        if (!hasText && !hasFiles) return;
        if (currentChatTarget == null) return;

        SecretKey sharedKey = null;
        if (!"ALL".equals(currentChatTarget)) {
            sharedKey = nettyClient.getSharedAesKey(currentChatTarget);
            if (sharedKey == null) {
                appendLogMessage("⚠️ 未建立加密通道，无法发送。");
                return;
            }
        }

        try {
            // 1. 【优先】发送暂存的图片
            if (hasFiles) {
                for (File file : pendingFiles) {
                    byte[] fileContent = Files.readAllBytes(file.toPath());
                    String base64 = Base64.getEncoder().encodeToString(fileContent);
                    Image image = new Image(new ByteArrayInputStream(fileContent));

                    if ("ALL".equals(currentChatTarget)) {
                        appendLogMessage("暂不支持群聊发图");
                    } else {
                        // 构建带前缀的 Payload
                        String imgPayload = IMG_PREFIX + base64;

                        if (isBurn) {
                            // 阅后即焚图片 (不存库，只发送)
                            String encryptedPayload = EncryptionUtils.aesEncrypt(imgPayload, sharedKey);
                            BurnAfterReadMessage barMsg = new BurnAfterReadMessage(currentUserId, encryptedPayload);
                            barMsg.setTargetUserId(currentChatTarget);
                            nettyClient.sendMessage(barMsg);

                            // 本地显示
                            appendImageMessage(currentUserId, image, true);
                        } else {
                            // 普通图片 (发送 ImageMessage 协议以保证实时性，同时存库保证历史记录)

                            // A. 发送网络协议
                            ImageMessage imgMsg = new ImageMessage(currentUserId, base64, currentChatTarget);
                            nettyClient.sendMessage(imgMsg);

                            // B. 存入本地数据库 (作为加密文本，带前缀)
                            String encryptedPayload = EncryptionUtils.aesEncrypt(imgPayload, sharedKey);
                            DatabaseManager.saveEncryptedMessage(currentUserId, currentChatTarget, true, encryptedPayload);

                            // C. 本地显示
                            appendImageMessage(currentUserId, image, false);
                        }
                    }
                }
                // 发送完清空暂存
                pendingFiles.clear();
                pendingFileBox.getChildren().clear();
                updatePendingAreaVisibility();
            }

            // 2. 【其次】发送文本
            if (hasText) {
                if ("ALL".equals(currentChatTarget)) {
                    TextMessage groupMsg = new TextMessage(currentUserId, messageContent);
                    groupMsg.setTargetUserId("ALL");
                    nettyClient.sendMessage(groupMsg);
                } else {
                    String encryptedContent = EncryptionUtils.aesEncrypt(messageContent, sharedKey);
                    if (isBurn) {
                        BurnAfterReadMessage barMsg = new BurnAfterReadMessage(currentUserId, encryptedContent);
                        barMsg.setTargetUserId(currentChatTarget);
                        nettyClient.sendMessage(barMsg);
                        appendChatMessage(currentUserId, "🔥 " + messageContent);
                    } else {
                        TextMessage textMsg = new TextMessage(currentUserId, encryptedContent);
                        textMsg.setTargetUserId(currentChatTarget);
                        nettyClient.sendMessage(textMsg);
                        DatabaseManager.saveEncryptedMessage(currentUserId, currentChatTarget, true, encryptedContent);
                        appendChatMessage(currentUserId, messageContent);
                    }
                }
                messageInputField.clear();
            }

        } catch (Exception e) {
            appendLogMessage("❌ 发送失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ================= 消息接收处理 =================

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

        // --- 图片消息 (接收方) ---
        if (message instanceof ImageMessage) {
            ImageMessage imgMsg = (ImageMessage) message;
            String senderId = imgMsg.getSenderId();

            // 1. 尝试存入数据库 (以便历史记录加载)
            // 我们需要密钥来加密它存库，保持数据库一致性
            try {
                SecretKey key = nettyClient.getSharedAesKey(senderId);
                if (key != null) {
                    String imgPayload = IMG_PREFIX + imgMsg.getBase64Content();
                    String encrypted = EncryptionUtils.aesEncrypt(imgPayload, key);
                    DatabaseManager.saveEncryptedMessage(currentUserId, senderId, false, encrypted);
                }
            } catch (Exception e) {
                System.err.println("图片存库失败: " + e.getMessage());
            }

            // 2. UI 更新
            if (!senderId.equals(currentChatTarget)) {
                Platform.runLater(() -> {
                    unreadSenders.add(senderId);
                    userListView.refresh();
                });
                return;
            }
            try {
                byte[] imgBytes = Base64.getDecoder().decode(imgMsg.getBase64Content());
                Image image = new Image(new ByteArrayInputStream(imgBytes));
                appendImageMessage(senderId, image, false);
            } catch (Exception e) {
                appendLogMessage("❌ 图片接收失败");
            }
            return;
        }

        if (message instanceof TextMessage) {
            handleTextMessage((TextMessage) message);
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

    private void handleTextMessage(TextMessage textMsg) {
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
        String decrypted = decryptMessage(senderId, textMsg.getContent());

        // 虽然 TextMessage 通常不发图片，但为了兼容性，也检查一下前缀
        if (decrypted.startsWith(IMG_PREFIX)) {
            try {
                String base64 = decrypted.substring(IMG_PREFIX.length());
                byte[] imgBytes = Base64.getDecoder().decode(base64);
                Image image = new Image(new ByteArrayInputStream(imgBytes));
                appendImageMessage(senderId, image, false);
            } catch (Exception e) {
                appendChatMessage(senderId, "[图片加载失败]");
            }
        } else {
            appendChatMessage(senderId, decrypted);
        }
    }

    // ================= UI 渲染 =================

    private void refreshGroupChatView() {
        if (!"ALL".equals(currentChatTarget)) return;
        List<HBox> newItems = new ArrayList<>();
        if (this.showGroupHeader) newItems.add(createSystemBubble("📢 公共群聊频道 (消息10秒后销毁)"));

        List<String[]> snapshot = new ArrayList<>(activeGroupMessages);
        for (String[] msgData : snapshot) {
            String content = msgData[1];
            if (content.length() > 500 && !content.contains(" ")) {
                content = "[大段数据/图片]";
            }
            HBox bubble = createChatBubble(msgData[0], content);
            newItems.add(bubble);
        }
        Platform.runLater(() -> {
            chatListView.getItems().setAll(newItems);
            if (!newItems.isEmpty()) chatListView.scrollTo(newItems.size() - 1);
        });
    }

    // --- 【修改】历史记录加载：支持图片 ---
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
                        String decrypted = EncryptionUtils.aesDecrypt(record[1], sharedKey);
                        String sender = record[0].equals("1") ? currentUserId : targetId;

                        // 【检测图片前缀】
                        if (decrypted.startsWith(IMG_PREFIX)) {
                            try {
                                String base64 = decrypted.substring(IMG_PREFIX.length());
                                byte[] imgBytes = Base64.getDecoder().decode(base64);
                                Image image = new Image(new ByteArrayInputStream(imgBytes));
                                appendImageMessage(sender, image, false);
                            } catch (Exception e) {
                                appendChatMessage(sender, "[图片数据损坏]");
                            }
                        } else {
                            appendChatMessage(sender, decrypted);
                        }
                    } catch (Exception e) { }
                }
            }
        }

        if (pendingBurnMessages.containsKey(targetId)) {
            List<String> burns = pendingBurnMessages.remove(targetId);
            if (burns != null && !burns.isEmpty()) {
                appendLogMessage("🔥 收到 " + burns.size() + " 条阅后即焚");
                for (String enc : burns) displayBurnMessage(targetId, enc);
            }
        }
    }

    private void displayBurnMessage(String senderId, String encryptedContent) {
        String decrypted = decryptMessage(senderId, encryptedContent);

        // 阅后即焚图片检测
        if (decrypted.startsWith(IMG_PREFIX)) {
            String base64 = decrypted.substring(IMG_PREFIX.length());
            try {
                byte[] imgBytes = Base64.getDecoder().decode(base64);
                Image image = new Image(new ByteArrayInputStream(imgBytes));
                HBox bubble = appendImageMessage(senderId, image, true);

                PauseTransition pause = new PauseTransition(Duration.seconds(10));
                pause.setOnFinished(e -> {
                    if (currentChatTarget != null && currentChatTarget.equals(senderId)) {
                        Platform.runLater(() -> {
                            chatListView.getItems().remove(bubble);
                            appendLogMessage("阅后即焚图片已销毁");
                        });
                    }
                });
                pause.play();
                return;
            } catch (Exception e) {
                decrypted = "[图片解析失败]";
            }
        }

        HBox bubbleBox = appendChatMessage(senderId, "🔥 " + decrypted);
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

    // --- 气泡工厂 ---

    private HBox createChatBubble(String sender, String message) {
        boolean isMe = sender.equals(currentUserId);
        Label nameLabel = new Label(sender);
        nameLabel.setStyle("-fx-text-fill: #888888; -fx-font-size: 10px; -fx-padding: 0 0 2px 0;");

        Label msgLabel = new Label(message);
        msgLabel.setWrapText(true);
        msgLabel.setMaxWidth(400);
        String commonStyle = "-fx-font-size: 14px; -fx-padding: 8px 12px; -fx-background-radius: 10px; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.3), 3, 0, 1, 1);";
        if (isMe) {
            msgLabel.setStyle(commonStyle + "-fx-background-color: #00ff00; -fx-text-fill: black; -fx-font-weight: bold; -fx-background-radius: 10px 0 10px 10px;");
        } else {
            msgLabel.setStyle(commonStyle + "-fx-background-color: #444444; -fx-text-fill: white; -fx-background-radius: 0 10px 10px 10px;");
        }

        VBox vBox = new VBox(2, nameLabel, msgLabel);
        vBox.setAlignment(isMe ? Pos.TOP_RIGHT : Pos.TOP_LEFT);
        HBox container = new HBox(vBox);
        container.setAlignment(isMe ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        return container;
    }

    private HBox createImageBubble(String sender, Image image, boolean isBurn) {
        boolean isMe = sender.equals(currentUserId);
        Label nameLabel = new Label(sender + (isBurn ? " (🔥)" : ""));
        nameLabel.setStyle("-fx-text-fill: #888888; -fx-font-size: 10px; -fx-padding: 0 0 2px 0;");

        ImageView imageView = new ImageView(image);
        imageView.setPreserveRatio(true);
        imageView.setFitWidth(200);
        if (image.getHeight() > 300) imageView.setFitHeight(300);

        imageView.setCursor(javafx.scene.Cursor.HAND);
        imageView.setOnMouseClicked(e -> showLargeImage(image));

        VBox vBox = new VBox(2, nameLabel, imageView);
        vBox.setAlignment(isMe ? Pos.TOP_RIGHT : Pos.TOP_LEFT);

        String style = "-fx-padding: 5px; -fx-background-radius: 10px; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.3), 3, 0, 1, 1);";
        if (isBurn) style += "-fx-border-color: red; -fx-border-width: 2px;";

        if (isMe) vBox.setStyle(style + "-fx-background-color: #004400;");
        else vBox.setStyle(style + "-fx-background-color: #333333;");

        HBox container = new HBox(vBox);
        container.setAlignment(isMe ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        return container;
    }

    private void showLargeImage(Image image) {
        Stage stage = new Stage();
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("查看图片");

        ImageView fullView = new ImageView(image);
        fullView.setPreserveRatio(true);
        fullView.setFitWidth(800);
        fullView.setFitHeight(600);

        StackPane root = new StackPane(fullView);
        root.setStyle("-fx-background-color: black;");
        root.setOnMouseClicked(e -> stage.close());

        Scene scene = new Scene(root, 800, 600);
        stage.setScene(scene);
        stage.show();
    }

    private HBox createSystemBubble(String message) {
        Label logLabel = new Label(message);
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

    private HBox appendImageMessage(String sender, Image image, boolean isBurn) {
        HBox bubble = createImageBubble(sender, image, isBurn);
        Platform.runLater(() -> {
            chatListView.getItems().add(bubble);
            chatListView.scrollTo(chatListView.getItems().size() - 1);
        });
        return bubble;
    }

    private void appendLogMessage(String message) {
        if (message.length() > 200 && !message.contains(" ")) {
            message = "[图片数据]";
        }
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