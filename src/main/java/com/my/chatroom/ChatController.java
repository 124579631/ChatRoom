package com.my.chatroom;

import javafx.animation.FadeTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
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
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class ChatController {

    @FXML private ListView<String> userListView;
    @FXML private ListView<HBox> chatListView;
    @FXML private TextField messageInputField;
    @FXML private Button sendButton;
    @FXML private Button burnButton;
    @FXML private Text chatTargetLabel;
    @FXML private ScrollPane pendingFileScroll;
    @FXML private HBox pendingFileBox;

    // 【新增】多选工具栏相关组件
    @FXML private HBox normalChatHeader;
    @FXML private HBox selectionToolbar;
    @FXML private Label selectionCountLabel;

    private Client nettyClient;
    private String currentUserId;
    private String currentChatTarget;
    private final ObservableList<String> onlineUsers = FXCollections.observableArrayList();

    private static final SimpleDateFormat DISPLAY_SDF = new SimpleDateFormat("MM月dd日 HH:mm");
    private static final SimpleDateFormat DB_SDF = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private long lastHeaderTime = 0;

    private final List<String[]> activeGroupMessages = new ArrayList<>();
    private boolean showGroupHeader = false;
    private final Map<String, Integer> unreadCounts = new ConcurrentHashMap<>();
    private final Map<String, List<String>> pendingBurnMessages = new ConcurrentHashMap<>();
    private final List<File> pendingFiles = new ArrayList<>();

    // 【新增】多选模式状态
    private boolean isSelectionMode = false;
    private final Set<HBox> selectedBubbles = new HashSet<>();

    private static final String IMG_PREFIX = "::IMG::";
    private static final String BURN_ICON = "⌛";

    @FXML
    public void initialize() {
        DB_SDF.setTimeZone(TimeZone.getTimeZone("UTC"));
        userListView.setItems(onlineUsers);
        chatTargetLabel.setText("未选择会话");

        chatListView.setFocusTraversable(false);
        chatListView.setCellFactory(lv -> new ListCell<HBox>() {
            @Override
            protected void updateItem(HBox item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    setStyle("-fx-background-color: transparent; -fx-padding: 0;");
                } else {
                    setGraphic(item);
                    // 确保 ListView 不会拦截右键事件
                    setStyle("-fx-background-color: transparent; -fx-padding: 5px 10px;");
                }
            }
        });

        // 用户列表渲染 (保持不变)
        userListView.setCellFactory(lv -> new ListCell<String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null); setGraphic(null);
                } else {
                    String rawId = item.replace(" (我)", "");
                    boolean isMe = item.contains("(我)");
                    StackPane avatar = createAvatar(rawId);
                    Label nameLabel = new Label(rawId + (isMe ? " (我)" : ""));
                    nameLabel.getStyleClass().add("user-name-label");
                    Region spacer = new Region();
                    HBox.setHgrow(spacer, Priority.ALWAYS);
                    HBox container = new HBox(12, avatar, nameLabel, spacer);
                    container.setAlignment(Pos.CENTER_LEFT);
                    if (unreadCounts.containsKey(rawId)) {
                        int count = unreadCounts.get(rawId);
                        if (count > 0) {
                            Label badge = createBadge(count > 99 ? "99+" : String.valueOf(count));
                            container.getChildren().add(badge);
                            nameLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #333333;");
                        }
                    }
                    setGraphic(container);
                    setText(null);
                }
            }
        });

        userListView.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
            if (newV != null) {
                // 切换用户时退出多选模式
                exitSelectionMode();
                String realTargetId = newV.replace(" (我)", "");
                if (!realTargetId.equals(currentChatTarget)) switchChatTarget(realTargetId);
            }
        });
    }

    private StackPane createAvatar(String userId) {
        int hash = userId.hashCode();
        int r = (hash & 0xFF0000) >> 16;
        int g = (hash & 0x00FF00) >> 8;
        int b = hash & 0x0000FF;
        Color color = Color.rgb((r + 100) / 2, (g + 100) / 2, (b + 200) / 2);
        Circle bg = new Circle(20, color);
        String initial = userId.isEmpty() ? "?" : userId.substring(0, 1).toUpperCase();
        Label letter = new Label(initial);
        letter.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 16px;");
        return new StackPane(bg, letter);
    }

    private Label createBadge(String text) {
        Label badge = new Label(text);
        badge.getStyleClass().add("unread-badge");
        return badge;
    }

    // 【修改 2】添加时间戳时，设置 UserData 标记，方便后续查找删除
    private void checkAndAddTimestamp(long msgTime) {
        if (msgTime - lastHeaderTime > 30 * 60 * 1000) {
            String timeStr = DISPLAY_SDF.format(new Date(msgTime));
            HBox timeBubble = createSystemBubble(timeStr);
            // 标记这个 HBox 是一个时间戳
            timeBubble.setUserData("TIMESTAMP");
            Platform.runLater(() -> chatListView.getItems().add(timeBubble));
            lastHeaderTime = msgTime;
        }
    }

    // ... (handleJoinPublicChat, handleSelectFileAction 等方法保持不变，省略) ...
    @FXML public void handleJoinPublicChat() {
        userListView.getSelectionModel().clearSelection();
        switchChatTarget("ALL");
        chatTargetLabel.setText("📢 公共广场");
        this.showGroupHeader = true;
        refreshGroupChatView();
        // 公共频道不记录未读
        unreadCounts.remove("ALL");
    }

    @FXML public void handleSelectFileAction() {
        if (currentChatTarget == null) return;
        FileChooser fileChooser = new FileChooser();
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg", "*.gif"));
        List<File> files = fileChooser.showOpenMultipleDialog(sendButton.getScene().getWindow());
        if (files != null) {
            for (File file : files) {
                if (file.length() > 5 * 1024 * 1024) continue;
                pendingFiles.add(file);
                addFilePreview(file);
            }
            updatePendingAreaVisibility();
        }
    }

    private void addFilePreview(File file) { /* ...同原代码... */
        try {
            Image thumb = new Image(file.toURI().toString(), 60, 60, true, true);
            ImageView iv = new ImageView(thumb);
            javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle(60, 60);
            clip.setArcWidth(10); clip.setArcHeight(10);
            iv.setClip(clip);
            Button removeBtn = new Button("✕");
            removeBtn.getStyleClass().add("remove-file-btn");
            removeBtn.setOnAction(e -> {
                pendingFiles.remove(file);
                updatePendingAreaVisibility();
                pendingFileBox.getChildren().clear();
                for (File f : pendingFiles) addFilePreview(f);
            });
            StackPane stack = new StackPane(iv, removeBtn);
            StackPane.setAlignment(removeBtn, Pos.TOP_RIGHT);
            pendingFileBox.getChildren().add(stack);
        } catch (Exception e) {}
    }
    private void updatePendingAreaVisibility() {
        boolean hasFiles = !pendingFiles.isEmpty();
        pendingFileScroll.setVisible(hasFiles);
        pendingFileScroll.setManaged(hasFiles);
    }

    @FXML private void handleSendMessageAction() { sendMsg(false); }
    @FXML private void handleBurnMessageAction() { sendMsg(true); }

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
                nettyClient.sendMessage(new KeyExchangeRequest(currentUserId, currentChatTarget));
                appendLogMessage("正在建立加密通道，请稍后重试...");
                return;
            }
        }

        try {
            long now = System.currentTimeMillis();
            checkAndAddTimestamp(now);

            // 1. 处理图片发送
            if (hasFiles) {
                for (File file : pendingFiles) {
                    byte[] fileContent = Files.readAllBytes(file.toPath());
                    String base64 = Base64.getEncoder().encodeToString(fileContent);
                    Image image = new Image(new ByteArrayInputStream(fileContent));

                    if ("ALL".equals(currentChatTarget)) {
                        appendLogMessage("群聊暂不支持发图");
                    } else {
                        String imgPayload = IMG_PREFIX + base64;
                        if (isBurn) {
                            String encryptedPayload = EncryptionUtils.aesEncrypt(imgPayload, sharedKey);
                            BurnAfterReadMessage barMsg = new BurnAfterReadMessage(currentUserId, encryptedPayload);
                            barMsg.setTargetUserId(currentChatTarget);
                            nettyClient.sendMessage(barMsg);
                            // 阅后即焚不存数据库，本地直接显示
                            appendImageMessage(currentUserId, image, true, -1);
                        } else {
                            ImageMessage imgMsg = new ImageMessage(currentUserId, base64, currentChatTarget);
                            nettyClient.sendMessage(imgMsg);

                            // 【FIX】如果是发给自己，依靠服务器回显处理，本地不保存不显示
                            if (currentChatTarget.equals(currentUserId)) {
                                continue;
                            }

                            String encryptedPayload = EncryptionUtils.aesEncrypt(imgPayload, sharedKey);
                            long msgId = DatabaseManager.saveEncryptedMessage(currentUserId, currentChatTarget, true, encryptedPayload);
                            appendImageMessage(currentUserId, image, false, msgId);
                        }
                    }
                }
                pendingFiles.clear();
                pendingFileBox.getChildren().clear();
                updatePendingAreaVisibility();
            }

            // 2. 处理文本发送
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
                        appendChatMessage(currentUserId, BURN_ICON + " " + messageContent, -1);
                    } else {
                        TextMessage textMsg = new TextMessage(currentUserId, encryptedContent);
                        textMsg.setTargetUserId(currentChatTarget);
                        nettyClient.sendMessage(textMsg);

                        // 【FIX】如果是发给自己，依靠服务器回显处理，本地不再重复保存和显示
                        if (currentChatTarget.equals(currentUserId)) {
                            messageInputField.clear();
                            return;
                        }

                        long msgId = DatabaseManager.saveEncryptedMessage(currentUserId, currentChatTarget, true, encryptedContent);
                        appendChatMessage(currentUserId, messageContent, msgId);
                    }
                }
                messageInputField.clear();
            }
        } catch (Exception e) { e.printStackTrace(); }
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

        // --- 图片消息 ---
        if (message instanceof ImageMessage) {
            ImageMessage imgMsg = (ImageMessage) message;
            String senderId = imgMsg.getSenderId();

            long msgId = -1;
            try {
                if (!"ALL".equals(imgMsg.getTargetUserId())) {
                    SecretKey key = nettyClient.getSharedAesKey(senderId);
                    if (key != null) {
                        String imgPayload = IMG_PREFIX + imgMsg.getBase64Content();
                        String encrypted = EncryptionUtils.aesEncrypt(imgPayload, key);
                        // 保存并获取 ID
                        msgId = DatabaseManager.saveEncryptedMessage(currentUserId, senderId, false, encrypted);
                    }
                }
            } catch (Exception e) { }

            if (!senderId.equals(currentChatTarget)) {
                // 【修改 3】增加未读计数
                incrementUnread(senderId);
                return;
            }
            try {
                checkAndAddTimestamp(System.currentTimeMillis());
                byte[] imgBytes = Base64.getDecoder().decode(imgMsg.getBase64Content());
                Image image = new Image(new ByteArrayInputStream(imgBytes));
                appendImageMessage(senderId, image, false, msgId);
            } catch (Exception e) { }
            return;
        }

        // --- 文本消息 ---
        if (message instanceof TextMessage) {
            TextMessage textMsg = (TextMessage) message;
            String senderId = textMsg.getSenderId();
            if ("SYSTEM".equals(senderId)) {
                if (currentChatTarget != null) appendLogMessage(textMsg.getContent());
                return;
            }
            if ("ALL".equals(textMsg.getTargetUserId())) {
                handleGroupMessage(textMsg);
                return;
            }

            // 保存并获取 ID
            long msgId = DatabaseManager.saveEncryptedMessage(currentUserId, senderId, false, textMsg.getContent());

            if (!senderId.equals(currentChatTarget)) {
                // 【修改 3】增加未读计数
                incrementUnread(senderId);
                return;
            }

            checkAndAddTimestamp(System.currentTimeMillis());
            String decrypted = decryptMessage(senderId, textMsg.getContent());
            if (decrypted.startsWith(IMG_PREFIX)) {
                try {
                    String base64 = decrypted.substring(IMG_PREFIX.length());
                    byte[] imgBytes = Base64.getDecoder().decode(base64);
                    Image image = new Image(new ByteArrayInputStream(imgBytes));
                    appendImageMessage(senderId, image, false, msgId);
                } catch (Exception e) { appendChatMessage(senderId, "[图片加载失败]", msgId); }
            } else {
                appendChatMessage(senderId, decrypted, msgId);
            }
            return;
        }

        // --- 阅后即焚 ---
        if (message instanceof BurnAfterReadMessage) {
            BurnAfterReadMessage burnMsg = (BurnAfterReadMessage) message;
            String senderId = burnMsg.getSenderId();
            if (!senderId.equals(currentChatTarget)) {
                // 【修改 3】增加未读计数
                incrementUnread(senderId);
                pendingBurnMessages.computeIfAbsent(senderId, k -> new ArrayList<>()).add(burnMsg.getEncryptedContent());
                return;
            }
            displayBurnMessage(senderId, burnMsg.getEncryptedContent());
        }
    }

    // 【修改 3】未读计数辅助方法
    private void incrementUnread(String senderId) {
        Platform.runLater(() -> {
            unreadCounts.put(senderId, unreadCounts.getOrDefault(senderId, 0) + 1);
            userListView.refresh();
        });
    }

    private void handleGroupMessage(TextMessage textMsg) {
        Platform.runLater(() -> {
            String time = DISPLAY_SDF.format(new Date());
            String[] msgData = new String[]{textMsg.getSenderId(), textMsg.getContent(), time};
            activeGroupMessages.add(msgData);
            if ("ALL".equals(currentChatTarget)) refreshGroupChatView();
            PauseTransition pause = new PauseTransition(Duration.seconds(10));
            pause.setOnFinished(e -> {
                activeGroupMessages.remove(msgData);
                if ("ALL".equals(currentChatTarget)) refreshGroupChatView();
            });
            pause.play();
        });
    }

    // ================= UI 渲染 =================

    private void switchChatTarget(String targetId) {
        this.currentChatTarget = targetId;
        this.lastHeaderTime = 0;

        // 【修改 3】清除该用户的未读计数
        if (unreadCounts.containsKey(targetId)) {
            unreadCounts.remove(targetId);
            userListView.refresh();
        }

        if ("ALL".equals(targetId)) {
            chatTargetLabel.setText("📢 公共广场");
            refreshGroupChatView();
            return;
        }

        chatTargetLabel.setText(targetId);
        chatListView.getItems().clear();

        SecretKey sharedKey = nettyClient.getSharedAesKey(targetId);
        if (sharedKey == null) {
            appendLogMessage("正在安全握手...");
            nettyClient.sendMessage(new KeyExchangeRequest(currentUserId, targetId));
        } else {
            // 【修改 1】读取历史记录 (包含 ID)
            List<String[]> history = DatabaseManager.getEncryptedHistory(currentUserId, targetId);
            for (String[] record : history) {
                try {
                    // record: [0]=isSender, [1]=content, [2]=timestamp, [3]=id
                    String dbTimeStr = record[2];
                    long msgId = Long.parseLong(record[3]); // 解析 ID

                    if (dbTimeStr != null) {
                        try {
                            Date date = DB_SDF.parse(dbTimeStr);
                            checkAndAddTimestamp(date.getTime());
                        } catch (Exception e) {}
                    }

                    String decrypted = EncryptionUtils.aesDecrypt(record[1], sharedKey);
                    String sender = record[0].equals("1") ? currentUserId : targetId;

                    if (decrypted.startsWith(IMG_PREFIX)) {
                        try {
                            String base64 = decrypted.substring(IMG_PREFIX.length());
                            byte[] imgBytes = Base64.getDecoder().decode(base64);
                            Image image = new Image(new ByteArrayInputStream(imgBytes));
                            appendImageMessage(sender, image, false, msgId);
                        } catch (Exception e) { }
                    } else {
                        appendChatMessage(sender, decrypted, msgId);
                    }
                } catch (Exception e) {}
            }
        }

        if (pendingBurnMessages.containsKey(targetId)) {
            List<String> burns = pendingBurnMessages.remove(targetId);
            if (burns != null) for (String enc : burns) displayBurnMessage(targetId, enc);
        }
    }

    private void refreshGroupChatView() {
        if (!"ALL".equals(currentChatTarget)) return;
        List<HBox> newItems = new ArrayList<>();
        if (this.showGroupHeader) newItems.add(createSystemBubble("公共频道 - 消息不做存储"));

        for (String[] msgData : activeGroupMessages) {
            // 群聊没有 ID (传 -1)，也不支持右键删除
            HBox bubble = createChatBubble(msgData[0], msgData[1], -1);
            newItems.add(bubble);
        }
        Platform.runLater(() -> {
            chatListView.getItems().setAll(newItems);
            if (!newItems.isEmpty()) chatListView.scrollTo(newItems.size() - 1);
        });
    }

    // --- 气泡(支持 ID 和右键菜单) ---

    private HBox createChatBubble(String sender, String message, long msgId) {
        boolean isMe = sender.equals(currentUserId);
        Label nameLabel = null;
        if (!isMe && "ALL".equals(currentChatTarget)) {
            nameLabel = new Label(sender);
            nameLabel.getStyleClass().add("sender-name");
        }

        Label msgLabel = new Label(message);
        msgLabel.setWrapText(true);
        msgLabel.setMaxWidth(450);
        msgLabel.getStyleClass().add(isMe ? "bubble-me" : "bubble-other");

        VBox vBox = new VBox(2);
        if (nameLabel != null) vBox.getChildren().add(nameLabel);
        vBox.getChildren().add(msgLabel);

        HBox container = new HBox(vBox);
        container.setAlignment(isMe ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);

        // 绑定 ID
        if (msgId != -1) container.setUserData(msgId);

        // 【核心修复 2】将交互逻辑绑定在 container (HBox) 上，确保点击范围够大且不仅限于 Label
        configureBubbleInteraction(container, msgLabel, message, msgId, false);

        animateBubble(container);
        return container;
    }

    private HBox createImageBubble(String sender, Image image, boolean isBurn, long msgId) {
        boolean isMe = sender.equals(currentUserId);
        ImageView imageView = new ImageView(image);
        imageView.setPreserveRatio(true);
        imageView.setFitWidth(250);
        if (image.getHeight() > 350) imageView.setFitHeight(350);
        javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle(imageView.getFitWidth(), imageView.getFitHeight());
        clip.setArcWidth(20); clip.setArcHeight(20);
        imageView.setClip(clip);

        VBox vBox = new VBox(imageView);
        vBox.getStyleClass().add(isMe ? "bubble-image-me" : "bubble-image-other");
        if (isBurn) vBox.getStyleClass().add("bubble-burn");

        HBox container = new HBox(vBox);
        container.setAlignment(isMe ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);

        if (msgId != -1 && !isBurn) {
            container.setUserData(msgId);
            // 绑定交互
            configureBubbleInteraction(container, imageView, "[图片]", msgId, true);
        } else if (isBurn) {
            // 阅后即焚只有点击查看大图，没有右键菜单
            imageView.setCursor(javafx.scene.Cursor.HAND);
            imageView.setOnMouseClicked(e -> showLargeImage(image));
        }

        animateBubble(container);
        return container;
    }

    /**
     * 统一配置气泡的点击与右键交互
     */
    private void configureBubbleInteraction(HBox container, Node contentNode, String contentStr, long msgId, boolean isImage) {
        // 1. 鼠标点击事件 (处理多选 + 查看图片)
        container.setOnMouseClicked(e -> {
            if (isSelectionMode) {
                // 多选模式下：左键点击即选中/取消
                if (e.getButton() == MouseButton.PRIMARY) {
                    toggleBubbleSelection(container);
                    e.consume();
                }
            } else {
                // 普通模式下：点击图片查看大图
                if (isImage && e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 1) {
                    if (contentNode instanceof ImageView) {
                        showLargeImage(((ImageView) contentNode).getImage());
                    }
                }
            }
        });

        // 2. 右键菜单
        ContextMenu contextMenu = new ContextMenu();

        // 选项：多选
        MenuItem selectItem = new MenuItem("多选");
        selectItem.setOnAction(e -> {
            enterSelectionMode();
            toggleBubbleSelection(container); // 默认选中当前这条
        });

        // 选项：复制
        MenuItem copyItem = new MenuItem("复制");
        copyItem.setOnAction(e -> copyContentToClipboard(contentStr));

        // 选项：转发
        MenuItem forwardItem = new MenuItem("转发");
        forwardItem.setOnAction(e -> showForwardDialog(Collections.singletonList(contentStr)));

        // 选项：删除 (已修改名称)
        MenuItem deleteItem = new MenuItem("删除");
        deleteItem.setStyle("-fx-text-fill: red;");
        deleteItem.setOnAction(e -> handleDeleteAction(msgId, container));

        contextMenu.getItems().addAll(selectItem, new SeparatorMenuItem(), copyItem, forwardItem, new SeparatorMenuItem(), deleteItem);

        // 绑定到 Container，覆盖整个气泡区域
        container.setOnContextMenuRequested(e -> {
            if (!isSelectionMode) {
                contextMenu.show(container, e.getScreenX(), e.getScreenY());
            }
        });
    }

    // -----------------------------------------------------------------------
    // 【多选模式逻辑】
    // -----------------------------------------------------------------------

    private void enterSelectionMode() {
        isSelectionMode = true;
        selectedBubbles.clear();

        // 切换顶部栏
        normalChatHeader.setVisible(false);
        normalChatHeader.setManaged(false);
        selectionToolbar.setVisible(true);
        selectionToolbar.setManaged(true);

        updateSelectionCount();
    }

    @FXML
    private void exitSelectionMode() {
        isSelectionMode = false;
        // 清除所有选中样式
        for (HBox bubble : selectedBubbles) {
            Node box = bubble.getChildren().get(0); // VBox
            box.getStyleClass().remove("bubble-selected");
        }
        selectedBubbles.clear();

        // 还原顶部栏
        selectionToolbar.setVisible(false);
        selectionToolbar.setManaged(false);
        normalChatHeader.setVisible(true);
        normalChatHeader.setManaged(true);
    }

    private void toggleBubbleSelection(HBox bubble) {
        Node box = bubble.getChildren().get(0); // 获取内部的 VBox (带样式的部分)
        if (selectedBubbles.contains(bubble)) {
            selectedBubbles.remove(bubble);
            box.getStyleClass().remove("bubble-selected");
        } else {
            selectedBubbles.add(bubble);
            box.getStyleClass().add("bubble-selected");
        }
        updateSelectionCount();
    }

    private void updateSelectionCount() {
        selectionCountLabel.setText("已选择 " + selectedBubbles.size() + " 条");
    }

    // --- 批量操作 ---

    @FXML
    private void handleBatchCopy() {
        if (selectedBubbles.isEmpty()) return;
        // 按照 ListView 中的顺序排序
        List<HBox> sortedBubbles = sortBubblesByOrder(selectedBubbles);

        StringBuilder sb = new StringBuilder();
        for (HBox bubble : sortedBubbles) {
            String text = extractTextFromBubble(bubble);
            sb.append(text).append("\n");
        }
        copyContentToClipboard(sb.toString().trim());
        exitSelectionMode();
    }

    @FXML
    private void handleBatchDelete() {
        if (selectedBubbles.isEmpty()) return;

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "确定要彻底删除这 " + selectedBubbles.size() + " 条消息吗？\n删除后无法恢复。");
        alert.initOwner(sendButton.getScene().getWindow());
        Optional<ButtonType> result = alert.showAndWait();

        if (result.isPresent() && result.get() == ButtonType.OK) {
            List<HBox> toDelete = new ArrayList<>(selectedBubbles);
            for (HBox bubble : toDelete) {
                Object userData = bubble.getUserData();
                if (userData instanceof Long) {
                    handleDeleteAction((Long) userData, bubble);
                }
            }
            exitSelectionMode();
        }
    }

    @FXML
    private void handleBatchForward() {
        if (selectedBubbles.isEmpty()) return;
        List<HBox> sortedBubbles = sortBubblesByOrder(selectedBubbles);
        List<String> contents = sortedBubbles.stream()
                .map(this::extractTextFromBubble)
                .collect(Collectors.toList());

        showForwardDialog(contents);
    }

    // 辅助：按屏幕显示顺序排序选中的气泡
    private List<HBox> sortBubblesByOrder(Set<HBox> bubbles) {
        return chatListView.getItems().stream()
                .filter(bubbles::contains)
                .collect(Collectors.toList());
    }

    // 辅助：从气泡提取文本
    private String extractTextFromBubble(HBox bubble) {
        VBox vBox = (VBox) bubble.getChildren().get(0);
        for (Node node : vBox.getChildren()) {
            if (node instanceof Label && !node.getStyleClass().contains("sender-name")) {
                return ((Label) node).getText();
            } else if (node instanceof ImageView) {
                return "[图片]"; // 图片暂只支持作为文本占位符转发
            }
        }
        return "";
    }

    // -----------------------------------------------------------------------
    // 【修复后的通用逻辑】
    // -----------------------------------------------------------------------

    private void copyContentToClipboard(String text) {
        ClipboardContent cc = new ClipboardContent();
        cc.putString(text);
        Clipboard.getSystemClipboard().setContent(cc);
    }

    /**
     * 通用转发对话框与逻辑
     * 修复点：允许转发给自己、转发给群，并正确处理加密
     */
    private void showForwardDialog(List<String> contents) {
        if (onlineUsers.isEmpty()) {
            new Alert(Alert.AlertType.WARNING, "没有在线用户可转发").show();
            return;
        }

        List<String> choices = new ArrayList<>(onlineUsers);
        // 如果不在 choices 里，补一个 "ALL (群聊)"，注意 onlineUsers 可能已经包含 "ALL" 相关的逻辑，这里做个保险
        if (choices.stream().noneMatch(s -> s.startsWith("ALL"))) {
            choices.add(0, "ALL (群聊)");
        }

        ChoiceDialog<String> dialog = new ChoiceDialog<>(choices.get(0), choices);
        dialog.setTitle("转发消息");
        dialog.setHeaderText("选择转发目标 (" + contents.size() + " 条)");
        dialog.setContentText("发送给:");
        dialog.initOwner(sendButton.getScene().getWindow());

        Optional<String> result = dialog.showAndWait();
        result.ifPresent(targetStr -> {
            String targetId = targetStr.replace(" (我)", "").replace("ALL (群聊)", "ALL");

            // 执行转发
            for (String content : contents) {
                doForwardMessage(targetId, content);
            }

            // 如果转发完退出多选模式
            if (isSelectionMode) exitSelectionMode();

            // 如果正好转发给当前窗口，刷新一下 UI (简单追加)
            if (targetId.equals(currentChatTarget)) {
                // 注意：这里由于是异步发送，其实 UI 刷新由 handleIncomingMessage 或 sendMsg 中的本地追加逻辑处理更好
                // 但为了确保看到自己发的，可以简单提示或不做额外操作，因为 doForwardMessage 里会存库
            } else {
                // 提示转发成功
                // appendLogMessage("已转发给 " + targetId);
            }
        });
    }

    private void doForwardMessage(String targetId, String content) {
        try {
            // 1. 群聊转发
            if ("ALL".equals(targetId)) {
                TextMessage groupMsg = new TextMessage(currentUserId, content);
                groupMsg.setTargetUserId("ALL");
                nettyClient.sendMessage(groupMsg);
                return;
            }

            // 2. 私聊转发
            SecretKey key = nettyClient.getSharedAesKey(targetId);
            if (key == null) {
                nettyClient.sendMessage(new KeyExchangeRequest(currentUserId, targetId));
                Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, "与 " + targetId + " 的安全通道未建立，转发失败，请重试").show());
                return;
            }

            String encrypted = EncryptionUtils.aesEncrypt(content, key);
            TextMessage msg = new TextMessage(currentUserId, encrypted);
            msg.setTargetUserId(targetId);
            nettyClient.sendMessage(msg);

            // 【FIX】如果是转发给自己，依靠服务器回显，本地不执行保存和UI更新
            if (targetId.equals(currentUserId)) {
                return;
            }

            // 3. 存入本地数据库 (作为发送者)
            long newId = DatabaseManager.saveEncryptedMessage(currentUserId, targetId, true, encrypted);

            // 4. UI 回显 (仅当目标是当前聊天对象时)
            if (targetId.equals(currentChatTarget)) {
                if (content.startsWith(IMG_PREFIX)) {
                    appendChatMessage(currentUserId, "[图片转发]", newId);
                } else {
                    appendChatMessage(currentUserId, content, newId);
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 创建右键菜单 (复制、转发、删除)
     */
    private void addContextMenu(Node node, String content, long msgId, HBox bubbleContainer, boolean isImage) {
        ContextMenu contextMenu = new ContextMenu();

        // 1. 复制
        MenuItem copyItem = new MenuItem("复制");
        copyItem.setOnAction(e -> {
            ClipboardContent cc = new ClipboardContent();
            if (isImage) {
                // 图片暂只支持作为文本标识复制，或者可以扩展为复制 image
                cc.putString("[图片]");
            } else {
                cc.putString(content);
            }
            Clipboard.getSystemClipboard().setContent(cc);
        });

        // 2. 转发
        MenuItem forwardItem = new MenuItem("转发");
        forwardItem.setOnAction(e -> handleForwardAction(content, isImage));

        // 3. 删除
        MenuItem deleteItem = new MenuItem("彻底删除");
        deleteItem.setStyle("-fx-text-fill: red;");
        deleteItem.setOnAction(e -> handleDeleteAction(msgId, bubbleContainer));

        contextMenu.getItems().addAll(copyItem, forwardItem, new SeparatorMenuItem(), deleteItem);

        // 绑定到 Node (Label 或 ImageView)
        node.setOnContextMenuRequested(e -> contextMenu.show(node, e.getScreenX(), e.getScreenY()));
    }

    /**
     * 处理转发逻辑
     */
    private void handleForwardAction(String content, boolean isImage) {
        if (onlineUsers.isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.WARNING, "没有在线用户可转发");
            alert.show();
            return;
        }

        // 弹出选择对话框
        List<String> choices = new ArrayList<>(onlineUsers);
        choices.add("ALL (群聊)");
        ChoiceDialog<String> dialog = new ChoiceDialog<>(choices.get(0), choices);
        dialog.setTitle("转发消息");
        dialog.setHeaderText("选择转发目标");
        dialog.setContentText("发送给:");

        Optional<String> result = dialog.showAndWait();
        result.ifPresent(target -> {
            String realTarget = target.replace(" (我)", "").replace("ALL (群聊)", "ALL");
            if (realTarget.equals(currentUserId)) return; // 不能转发给自己

            // 模拟发送流程
            String oldTarget = this.currentChatTarget;
            // 临时切换目标，发送完再切回来（或者直接后台发送）
            // 这里为了简单，直接后台发送
            SecretKey key = nettyClient.getSharedAesKey(realTarget);

            if ("ALL".equals(realTarget)) {
                nettyClient.sendMessage(new TextMessage(currentUserId, content)); // 群聊转发
            } else if (key != null) {
                try {
                    String enc = EncryptionUtils.aesEncrypt(content, key);
                    nettyClient.sendMessage(new TextMessage(currentUserId, enc));
                    DatabaseManager.saveEncryptedMessage(currentUserId, realTarget, true, enc);
                    if (realTarget.equals(currentChatTarget)) {
                        appendChatMessage(currentUserId, content, -1); // 刷新当前界面
                    }
                } catch (Exception ex) { ex.printStackTrace(); }
            } else {
                Alert alert = new Alert(Alert.AlertType.ERROR, "与目标 " + realTarget + " 未建立安全连接");
                alert.show();
            }
        });
    }

    /**
     * 处理删除逻辑
     * 优化了删除逻辑，确保即使数据库中已不存在（如重复消息），UI上也能被删除
     */
    private void handleDeleteAction(long msgId, HBox bubble) {
        // 1. 尝试数据库物理删除 (不关心返回值，旨在确保数据被清理)
        DatabaseManager.deleteMessage(msgId);

        // 2. 强制 UI 移除 (解决之前重复消息无法彻底删除的问题)
        int index = chatListView.getItems().indexOf(bubble);
        if (index >= 0) {
            chatListView.getItems().remove(index);
            cleanupTimestamps();
        }
    }

    // 清理多余时间戳的简单算法
    private void cleanupTimestamps() {
        // 倒序遍历防止索引错位
        for (int i = chatListView.getItems().size() - 1; i >= 0; i--) {
            HBox item = chatListView.getItems().get(i);
            boolean isTimestamp = "TIMESTAMP".equals(item.getUserData());

            if (isTimestamp) {
                // 如果是最后一个元素，或者下一个元素也是时间戳 -> 删除
                if (i == chatListView.getItems().size() - 1) {
                    chatListView.getItems().remove(i);
                } else {
                    HBox nextItem = chatListView.getItems().get(i + 1);
                    if ("TIMESTAMP".equals(nextItem.getUserData())) {
                        chatListView.getItems().remove(i);
                    }
                }
            }
        }
    }

    private void animateBubble(Node node) {
        FadeTransition ft = new FadeTransition(Duration.millis(300), node);
        ft.setFromValue(0.0);
        ft.setToValue(1.0);
        TranslateTransition tt = new TranslateTransition(Duration.millis(300), node);
        tt.setFromY(10);
        tt.setToY(0);
        ft.play();
        tt.play();
    }

    private HBox createSystemBubble(String message) {
        Label logLabel = new Label(message);
        logLabel.getStyleClass().add("bubble-system");
        HBox container = new HBox(logLabel);
        container.setAlignment(Pos.CENTER);
        return container;
    }

    // ... showLargeImage 保持不变 ...
    private void showLargeImage(Image image) {
        Stage stage = new Stage();
        stage.initModality(Modality.APPLICATION_MODAL);
        ImageView fullView = new ImageView(image);
        fullView.setPreserveRatio(true);
        fullView.setFitWidth(800); fullView.setFitHeight(600);
        StackPane root = new StackPane(fullView);
        root.setStyle("-fx-background-color: rgba(0,0,0,0.9);");
        root.setOnMouseClicked(e -> stage.close());
        Scene scene = new Scene(root, 800, 600);
        stage.setScene(scene);
        stage.show();
    }

    // 重构的 append 方法，支持传入 ID
    private HBox appendChatMessage(String sender, String message, long msgId) {
        HBox bubble = createChatBubble(sender, message, msgId);
        Platform.runLater(() -> { chatListView.getItems().add(bubble); chatListView.scrollTo(chatListView.getItems().size() - 1); });
        return bubble;
    }
    private HBox appendImageMessage(String sender, Image image, boolean isBurn, long msgId) {
        HBox bubble = createImageBubble(sender, image, isBurn, msgId);
        Platform.runLater(() -> { chatListView.getItems().add(bubble); chatListView.scrollTo(chatListView.getItems().size() - 1); });
        return bubble;
    }
    private void appendLogMessage(String message) {
        if (message.length() > 200) message = "...";
        HBox bubble = createSystemBubble(message);
        Platform.runLater(() -> { chatListView.getItems().add(bubble); chatListView.scrollTo(chatListView.getItems().size() - 1); });
    }

    private void displayBurnMessage(String senderId, String encryptedContent) {
        String decrypted = decryptMessage(senderId, encryptedContent);
        if (decrypted.startsWith(IMG_PREFIX)) { /*...*/ }
        else {
            // 【修改 4】显示图标为 ⌛
            HBox bubbleBox = appendChatMessage(senderId, BURN_ICON + " " + decrypted, -1);
            PauseTransition pause = new PauseTransition(Duration.seconds(10));
            pause.setOnFinished(e -> {
                if (currentChatTarget != null && currentChatTarget.equals(senderId)) {
                    Platform.runLater(() -> { chatListView.getItems().remove(bubbleBox); cleanupTimestamps(); });
                }
            });
            pause.play();
        }
    }

    private String decryptMessage(String senderId, String content) {
        SecretKey key = nettyClient.getSharedAesKey(senderId);
        if (key != null) { try { return EncryptionUtils.aesDecrypt(content, key); } catch(Exception e){} }
        return "🔒";
    }
}