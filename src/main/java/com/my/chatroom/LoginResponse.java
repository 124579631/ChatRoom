package com.my.chatroom;

/**
 * 登录响应协议 (LoginResponse)
 * 作用：服务器返回认证结果给客户端
 */
public class LoginResponse extends Message {

    private boolean success; // 认证是否成功
    private String message;  // 失败原因或成功信息

    public LoginResponse() {
        super();
        super.setType(MessageType.LOGIN_RESPONSE);
    }

    public LoginResponse(String userId, boolean success, String message) {
        super(MessageType.LOGIN_RESPONSE, userId);
        this.success = success;
        this.message = message;
    }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}