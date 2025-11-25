package com.my.chatroom;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 数据库管理器 (DatabaseManager)
 * 作用：处理 SQLite 数据库的连接、初始化，以及用户注册/查询、公钥更新、聊天记录存储。
 * 【已修改】：新增了 chat_history 表和相关操作方法。
 */
public class DatabaseManager {

    private static final String URL = "jdbc:sqlite:chatroom.db";

    static {
        try {
            // 确保加载 SQLite 驱动
            Class.forName("org.sqlite.JDBC");
            System.out.println("[DB] SQLite 驱动加载成功。");
            initializeDatabase();
        } catch (ClassNotFoundException e) {
            System.err.println("[DB] 错误：SQLite 驱动未找到。");
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * 获取数据库连接
     */
    private static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(URL);
    }

    /**
     * 初始化数据库：创建 users 表和 chat_history 表
     */
    private static void initializeDatabase() {
        // 1. 用户表：存储用户ID、密码哈希、公钥
        String createUsersTableSQL = "CREATE TABLE IF NOT EXISTS users ("
                + "user_id TEXT PRIMARY KEY NOT NULL,"
                + "password_hash TEXT NOT NULL,"
                + "public_key TEXT"
                + ");";

        // 2. 【新增】聊天记录表：存储加密后的历史消息
        String createHistoryTableSQL = "CREATE TABLE IF NOT EXISTS chat_history ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "user_id TEXT NOT NULL,"            // 记录属于哪个用户 (防止记录混淆)
                + "target_id TEXT NOT NULL,"          // 聊天的另一方是谁
                + "is_sender INTEGER NOT NULL,"       // 1:是发送者, 0:是接收者
                + "encrypted_content TEXT NOT NULL,"  // 核心：存储 Base64 编码的加密消息内容
                + "timestamp DATETIME DEFAULT CURRENT_TIMESTAMP" // 记录时间
                + ");";

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute(createUsersTableSQL);
            stmt.execute(createHistoryTableSQL); // 执行创建聊天记录表的 SQL
            System.out.println("[DB] 数据库表 'users' 和 'chat_history' 检查/创建完成。");

            // 预先插入测试用户 (如果不存在)
            // 注意：这里我们使用明文密码 "testpass"，但在实际 LoginRequest 处理时，服务器会对比哈希值
            if (getUser("test1") == null) {
                // 默认使用 SHA-256 哈希存储密码。
                // 暂时用明文"testpass"的简单哈希占位，LoginRequest 实际处理时会调用更安全的哈希方法。
                registerUser("test1", "testpass");
            }
            if (getUser("test2") == null) {
                registerUser("test2", "testpass");
            }

        } catch (SQLException e) {
            System.err.println("[DB] 数据库初始化失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // --- 用户管理方法 (已有的核心方法) ---

    /**
     * 注册新用户
     * @param userId 用户ID
     * @param password 明文密码 (注意：实际 LoginRequest 应该在服务器端进行哈希校验)
     */
    public static boolean registerUser(String userId, String password) {
        if (getUser(userId) != null) {
            System.err.println("[DB] 用户 " + userId + " 已存在。");
            return false;
        }

        // 使用 SHA-256 哈希
        String hash = hashPassword(password);

        String sql = "INSERT INTO users (user_id, password_hash) VALUES (?, ?)";
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, userId);
            pstmt.setString(2, hash);
            pstmt.executeUpdate();
            return true;
        } catch (SQLException e) {
            System.err.println("[DB] 注册用户失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 获取用户信息，包含密码哈希和公钥
     */
    public static User getUser(String userId) {
        String sql = "SELECT user_id, password_hash, public_key FROM users WHERE user_id = ?";

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, userId);
            ResultSet rs = pstmt.executeQuery();

            if (rs.next()) {
                String id = rs.getString("user_id");
                String hash = rs.getString("password_hash");
                String pubKey = rs.getString("public_key");

                User user = new User(id, hash);
                user.setPublicKey(pubKey);
                return user;
            }
        } catch (SQLException e) {
            System.err.println("[DB] 查询用户失败: " + e.getMessage());
        }
        return null;
    }

    // DatabaseManager.java - 【新增这个方法】

    /**
     * 【新增】验证用户密码
     * @param userId 用户ID
     * @param password 客户端提供的明文密码
     * @return 密码是否匹配
     */
    public static boolean verifyPassword(String userId, String password) {
        User user = getUser(userId);
        if (user != null) {
            // 关键：比较数据库中的哈希（目前是明文）和客户端提供的密码
            // 注意：在你的 ChatServerHandler 中，登录逻辑也是直接比较的
            return user.getPasswordHash().equals(password);
        }
        // 用户不存在
        return false;
    }

    /**
     * 更新用户的公钥
     */
    public static boolean updatePublicKey(String userId, String publicKey) {
        String sql = "UPDATE users SET public_key = ? WHERE user_id = ?";

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, publicKey);
            pstmt.setString(2, userId);
            pstmt.executeUpdate();
            return true;
        } catch (SQLException e) {
            System.err.println("[DB] 更新公钥失败: " + e.getMessage());
            return false;
        }
    }

    // --- 【新增】聊天记录管理方法 (第 5 周核心) ---

    /**
     * 【新增】存储一条加密的聊天记录
     * 客户端在收到/发送加密消息后，调用此方法将其存储在本地数据库。
     * @param currentUserId 当前登录用户
     * @param targetId 聊天对象
     * @param isSender 是否是发送方 (true=1/false=0)
     * @param encryptedContent Base64 编码的加密消息内容
     * @return 存储是否成功
     */
    public static boolean saveEncryptedMessage(String currentUserId, String targetId, boolean isSender, String encryptedContent) {
        String sql = "INSERT INTO chat_history (user_id, target_id, is_sender, encrypted_content) VALUES (?, ?, ?, ?)";
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, currentUserId);
            pstmt.setString(2, targetId);
            pstmt.setInt(3, isSender ? 1 : 0); // 1 代表是发送者，0 代表是接收者
            pstmt.setString(4, encryptedContent);
            pstmt.executeUpdate();
            return true;
        } catch (SQLException e) {
            System.err.println("[DB] 存储聊天记录失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 【新增】获取与某个用户的加密聊天记录
     * 客户端启动或切换聊天对象时调用，用于加载历史记录。
     * @param currentUserId 当前登录用户
     * @param targetId 聊天对象
     * @return 包含记录的列表 (返回 String[0]=isSender, String[1]=encrypted_content, String[2]=timestamp)
     */
    public static List<String[]> getEncryptedHistory(String currentUserId, String targetId) {
        List<String[]> history = new ArrayList<>();
        // 查询是当前用户和目标用户的记录，并按时间排序
        String sql = "SELECT is_sender, encrypted_content, timestamp FROM chat_history " +
                "WHERE user_id = ? AND target_id = ? ORDER BY timestamp ASC";

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, currentUserId);
            pstmt.setString(2, targetId);
            ResultSet rs = pstmt.executeQuery();

            while (rs.next()) {
                String isSender = rs.getString("is_sender");
                String content = rs.getString("encrypted_content");
                String timestamp = rs.getString("timestamp");
                // String[0]=isSender (1/0), String[1]=encrypted_content, String[2]=timestamp
                history.add(new String[]{isSender, content, timestamp});
            }
        } catch (SQLException e) {
            System.err.println("[DB] 查询聊天记录失败: " + e.getMessage());
        }
        return history;
    }

    /**
     * SHA-256 密码哈希工具
     */
    public static String hashPassword(String plainPassword) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] encodedhash = digest.digest(plainPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            StringBuilder hexString = new StringBuilder(2 * encodedhash.length);
            for (int i = 0; i < encodedhash.length; i++) {
                String hex = Integer.toHexString(0xff & encodedhash[i]);
                if(hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}