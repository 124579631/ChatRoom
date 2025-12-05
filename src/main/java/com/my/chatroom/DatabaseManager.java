package com.my.chatroom;

import java.sql.*;
import java.util.HashMap;
import java.util.Map;

/**
 * 数据库管理器 - 支持消息 ID 返回与删除
 */
public class DatabaseManager {

    private static final String URL = "jdbc:sqlite:chatroom.db";

    static {
        try {
            Class.forName("org.sqlite.JDBC");
            initializeDatabase();
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
    }

    private static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(URL);
    }

    private static void initializeDatabase() {
        // 表结构保持不变
        String createUsersTableSQL = "CREATE TABLE IF NOT EXISTS users ("
                + "user_id TEXT PRIMARY KEY NOT NULL,"
                + "password_hash TEXT NOT NULL,"
                + "public_key TEXT"
                + ");";

        String createHistoryTableSQL = "CREATE TABLE IF NOT EXISTS chat_history ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "user_id TEXT NOT NULL,"
                + "target_id TEXT NOT NULL,"
                + "is_sender INTEGER NOT NULL,"
                + "encrypted_content TEXT NOT NULL,"
                + "timestamp DATETIME DEFAULT CURRENT_TIMESTAMP"
                + ");";

        String createKeysTableSQL = "CREATE TABLE IF NOT EXISTS session_keys ("
                + "owner_id TEXT NOT NULL,"
                + "target_id TEXT NOT NULL,"
                + "key_blob TEXT NOT NULL,"
                + "PRIMARY KEY (owner_id, target_id)"
                + ");";

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(createUsersTableSQL);
            stmt.execute(createHistoryTableSQL);
            stmt.execute(createKeysTableSQL);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // --- 用户相关方法 (保持不变) ---
    public static boolean registerUser(String userId, String password) {
        if (getUser(userId) != null) return false;
        String hash = hashPassword(password);
        String sql = "INSERT INTO users (user_id, password_hash) VALUES (?, ?)";
        try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, userId);
            pstmt.setString(2, hash);
            pstmt.executeUpdate();
            return true;
        } catch (SQLException e) { return false; }
    }

    public static User getUser(String userId) {
        String sql = "SELECT user_id, password_hash, public_key FROM users WHERE user_id = ?";
        try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, userId);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                User user = new User(rs.getString("user_id"), rs.getString("password_hash"));
                user.setPublicKey(rs.getString("public_key"));
                return user;
            }
        } catch (SQLException e) {}
        return null;
    }

    public static boolean updatePublicKey(String userId, String publicKey) {
        String sql = "UPDATE users SET public_key = ? WHERE user_id = ?";
        try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, publicKey);
            pstmt.setString(2, userId);
            pstmt.executeUpdate();
            return true;
        } catch (SQLException e) { return false; }
    }

    // --- 消息相关方法 (核心修改) ---

    /**
     * 保存消息并返回生成的 ID
     * @return 消息的数据库 ID，失败返回 -1
     */
    public static long saveEncryptedMessage(String currentUserId, String targetId, boolean isSender, String encryptedContent) {
        String sql = "INSERT INTO chat_history (user_id, target_id, is_sender, encrypted_content) VALUES (?, ?, ?, ?)";
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) { // 关键：请求返回主键
            pstmt.setString(1, currentUserId);
            pstmt.setString(2, targetId);
            pstmt.setInt(3, isSender ? 1 : 0);
            pstmt.setString(4, encryptedContent);
            int affectedRows = pstmt.executeUpdate();

            if (affectedRows > 0) {
                try (ResultSet generatedKeys = pstmt.getGeneratedKeys()) {
                    if (generatedKeys.next()) {
                        return generatedKeys.getLong(1); // 返回 ID
                    }
                }
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return -1;
    }

    /**
     * 获取历史记录
     * @return List<String[]> -> [isSender, encrypted_content, timestamp, id]
     */
    public static java.util.List<String[]> getEncryptedHistory(String currentUserId, String targetId) {
        java.util.List<String[]> history = new java.util.ArrayList<>();
        // 【修改】增加了 id 字段
        String sql = "SELECT id, is_sender, encrypted_content, timestamp FROM chat_history WHERE user_id = ? AND target_id = ? ORDER BY timestamp ASC";
        try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, currentUserId);
            pstmt.setString(2, targetId);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                history.add(new String[]{
                        rs.getString("is_sender"),
                        rs.getString("encrypted_content"),
                        rs.getString("timestamp"),
                        String.valueOf(rs.getLong("id")) // 第4个元素是 ID
                });
            }
        } catch (SQLException e) {}
        return history;
    }

    /**
     * 【新增】彻底删除消息
     */
    public static boolean deleteMessage(long messageId) {
        String sql = "DELETE FROM chat_history WHERE id = ?";
        try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, messageId);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    // --- 密钥管理 (保持不变) ---
    public static boolean saveSessionKey(String ownerId, String targetId, String encryptedKeyBlob) {
        String sql = "INSERT OR REPLACE INTO session_keys (owner_id, target_id, key_blob) VALUES (?, ?, ?)";
        try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, ownerId);
            pstmt.setString(2, targetId);
            pstmt.setString(3, encryptedKeyBlob);
            pstmt.executeUpdate();
            return true;
        } catch (SQLException e) { return false; }
    }

    public static Map<String, String> getAllSessionKeys(String ownerId) {
        Map<String, String> keys = new HashMap<>();
        String sql = "SELECT target_id, key_blob FROM session_keys WHERE owner_id = ?";
        try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, ownerId);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                keys.put(rs.getString("target_id"), rs.getString("key_blob"));
            }
        } catch (SQLException e) {}
        return keys;
    }

    public static String hashPassword(String plainPassword) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] encodedhash = digest.digest(plainPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder(2 * encodedhash.length);
            for (byte b : encodedhash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}