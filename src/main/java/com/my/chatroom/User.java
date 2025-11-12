package com.my.chatroom;

/**
 * 用户实体类 (User)
 * 作用：对应数据库中的用户表结构，存储用户身份和安全信息
 */
public class User {

    private String userId;      // 用户的唯一标识符 (用户名/账号)
    private String passwordHash; // 密码的哈希值 (目前存明文，后续会改成哈希)
    private String clientAddress; // 当前连接的客户端地址 (IP:Port)，用于 Netty 内部识别
    private String publicKey;   // 用户的RSA公钥 (第4周使用)
    private boolean isLoggedIn; // 登录状态 (当前连接是否已认证)

    // 构造函数
    public User(String userId, String passwordHash) {
        this.userId = userId;
        this.passwordHash = passwordHash;
        this.isLoggedIn = false;
    }

    // --- 完整 Getter and Setter --- (Getter/Setter 必须加上)

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public String getClientAddress() { return clientAddress; }
    public void setClientAddress(String clientAddress) { this.clientAddress = clientAddress; }

    public String getPublicKey() { return publicKey; }
    public void setPublicKey(String publicKey) { this.publicKey = publicKey; }

    public boolean isLoggedIn() { return isLoggedIn; }
    public void setLoggedIn(boolean loggedIn) { isLoggedIn = loggedIn; }
}