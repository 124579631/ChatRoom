package com.my.chatroom;

/**
 * AES 密钥交换消息协议
 * 客户端 A 使用 B 的公钥加密 AES 密钥后，发送给客户端 B。
 */
public class AESKeyExchangeMessage extends Message {

    private String targetUserId;         // 接收方 B 的 ID
    private String encryptedAesKey;      // 用 B 的公钥 RSA 加密后的 AES 密钥 (Base64)

    public AESKeyExchangeMessage() {
        super();
        super.setType(MessageType.AES_KEY_EXCHANGE);
    }

    public AESKeyExchangeMessage(String senderId, String targetUserId, String encryptedAesKey) {
        super(MessageType.AES_KEY_EXCHANGE, senderId);
        this.targetUserId = targetUserId;
        this.encryptedAesKey = encryptedAesKey;
    }

    // Getters and Setters
    public String getTargetUserId() { return targetUserId; }
    public void setTargetUserId(String targetUserId) { this.targetUserId = targetUserId; }
    public String getEncryptedAesKey() { return encryptedAesKey; }
    public void setEncryptedAesKey(String encryptedAesKey) { this.encryptedAesKey = encryptedAesKey; }
}