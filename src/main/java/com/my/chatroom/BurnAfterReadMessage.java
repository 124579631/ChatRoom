package com.my.chatroom;

/**
 * 阅后即焚消息协议 (BurnAfterReadMessage)
 * 继承自 Message 基类，使用特定的 MessageType 标识。
 * 作用：承载加密后的“阅后即焚”内容。
 */
public class BurnAfterReadMessage extends Message {

    private String encryptedContent; // 加密后的消息内容
    private String targetUserId;     // 指定目标接收方

    public BurnAfterReadMessage() {
        super();
        super.setType(MessageType.BURN_AFTER_READ); // 设置为阅后即焚类型
    }

    public BurnAfterReadMessage(String senderId, String encryptedContent) {
        super(MessageType.BURN_AFTER_READ, senderId);
        this.encryptedContent = encryptedContent;
    }

    // GSON 需要无参构造函数

    // --- Getter and Setter ---
    public String getEncryptedContent() { return encryptedContent; }
    public void setEncryptedContent(String encryptedContent) { this.encryptedContent = encryptedContent; }

    public String getTargetUserId() { return targetUserId; }
    public void setTargetUserId(String targetUserId) { this.targetUserId = targetUserId; }
}