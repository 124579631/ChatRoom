package com.my.chatroom;

/**
 * 图片消息协议
 * 承载 Base64 编码的图片数据
 */
public class ImageMessage extends Message {

    private String base64Content; // 图片的 Base64 字符串
    private String targetUserId;  // 接收方

    public ImageMessage() {
        super();
        super.setType(MessageType.IMAGE_MESSAGE);
    }

    public ImageMessage(String senderId, String base64Content, String targetUserId) {
        super(MessageType.IMAGE_MESSAGE, senderId);
        this.base64Content = base64Content;
        this.targetUserId = targetUserId;
    }

    public String getBase64Content() { return base64Content; }
    public void setBase64Content(String base64Content) { this.base64Content = base64Content; }

    public String getTargetUserId() { return targetUserId; }
    public void setTargetUserId(String targetUserId) { this.targetUserId = targetUserId; }
}