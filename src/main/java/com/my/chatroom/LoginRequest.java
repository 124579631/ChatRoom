package com.my.chatroom;

/**
 * 登录请求协议 (LoginRequest)
 * 作用：客户端发送账号和密码进行认证
 */
public class LoginRequest extends Message {

    private String password; // 客户端发送的明文密码 (暂时，后续会考虑更安全的方式)
    private String publicKey; // 客户端上传的 RSA 公钥 Base64 字符串

    public LoginRequest() {
        super();
        super.setType(MessageType.LOGIN_REQUEST);
    }

    public LoginRequest(String userId, String password, String publicKey) {
        super(MessageType.LOGIN_REQUEST, userId);
        this.password = password;
        this.publicKey = publicKey;
    }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    // 【新增 Getter and Setter for publicKey】
    public String getPublicKey() { return publicKey; }
    public void setPublicKey(String publicKey) { this.publicKey = publicKey; }
}