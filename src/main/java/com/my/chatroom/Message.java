package com.my.chatroom;

import java.io.Serializable;

public class Message implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum MessageType {
        LOGIN_REQUEST,
        LOGIN_RESPONSE,
        KEY_EXCHANGE,
        TEXT_MESSAGE_ENCRYPTED,
        BURN_AFTER_READ,
        USER_LIST_UPDATE,
        KEY_EXCHANGE_REQUEST,   // 密钥交换请求
        KEY_EXCHANGE_RESPONSE,  // 密钥交换响应
        AES_KEY_EXCHANGE,
    }

    private MessageType type;
    private String senderId;
    private long timestamp;

    public Message() {
        this.timestamp = System.currentTimeMillis();
    }

    public Message(MessageType type) {
        this.type = type;
        this.timestamp = System.currentTimeMillis();
    }

    public Message(MessageType type, String senderId) {
        this.type = type;
        this.senderId = senderId;
        this.timestamp = System.currentTimeMillis();
    }

    // --- Getter and Setter ---
    public MessageType getType() { return type; }
    public void setType(MessageType type) { this.type = type; }

    public String getSenderId() { return senderId; }
    public void setSenderId(String senderId) { this.senderId = senderId; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
}