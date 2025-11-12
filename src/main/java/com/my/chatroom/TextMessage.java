package com.my.chatroom;

public class TextMessage extends Message {

    private String content;
    private String targetUserId; // 指定目标接收方

    public TextMessage() {
        super();
        super.setType(MessageType.TEXT_MESSAGE_ENCRYPTED);
    }

    public TextMessage(String senderId, String content) {
        super(MessageType.TEXT_MESSAGE_ENCRYPTED, senderId);
        this.content = content;
    }

    // --- Getter and Setter ---
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getTargetUserId() { return targetUserId; }
    public void setTargetUserId(String targetUserId) { this.targetUserId = targetUserId; }
}