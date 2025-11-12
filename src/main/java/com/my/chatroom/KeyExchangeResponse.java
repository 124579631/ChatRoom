package com.my.chatroom;

/**
 * 密钥交换响应协议
 * 服务器返回目标用户的公钥。
 */
public class KeyExchangeResponse extends Message {

    private boolean success;
    private String message;
    private String targetUserId;      // 目标用户 ID
    private String targetPublicKey;   // 目标用户的 Base64 编码公钥

    public KeyExchangeResponse() {
        super();
        super.setType(MessageType.KEY_EXCHANGE_RESPONSE);
    }

    public KeyExchangeResponse(String senderId, boolean success, String message, String targetUserId, String targetPublicKey) {
        super(MessageType.KEY_EXCHANGE_RESPONSE, senderId);
        this.success = success;
        this.message = message;
        this.targetUserId = targetUserId;
        this.targetPublicKey = targetPublicKey;
    }

    // Getters and Setters
    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getTargetUserId() { return targetUserId; }
    public void setTargetUserId(String targetUserId) { this.targetUserId = targetUserId; }
    public String getTargetPublicKey() { return targetPublicKey; }
    public void setTargetPublicKey(String targetPublicKey) { this.targetPublicKey = targetPublicKey; }
}