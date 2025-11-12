package com.my.chatroom;

/**
 * 密钥交换请求协议
 * 客户端 A 向服务器请求用户 B 的公钥。
 */
public class KeyExchangeRequest extends Message {

    private String targetUserId; // A 想要获取其公钥的用户 ID

    public KeyExchangeRequest() {
        super();
        super.setType(MessageType.KEY_EXCHANGE_REQUEST);
    }

    public KeyExchangeRequest(String senderId, String targetUserId) {
        super(MessageType.KEY_EXCHANGE_REQUEST, senderId);
        this.targetUserId = targetUserId;
    }

    public String getTargetUserId() {
        return targetUserId;
    }

    public void setTargetUserId(String targetUserId) {
        this.targetUserId = targetUserId;
    }
}