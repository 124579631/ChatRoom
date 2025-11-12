package com.my.chatroom;

import java.util.Base64;
import java.security.*;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.util.Arrays;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets; // 【已新增】解决 StandardCharsets 报错

/**
 * 加密工具类 (EncryptionUtils)
 * 作用：处理密钥对生成、Base64 编码/解码，以及非对称加密/解密。
 * 【已修改】：新增 generateAesKey() 方法；修复 StandardCharsets 导入错误。
 */
public class EncryptionUtils {

    // 算法常量
    private static final String RSA_ALGORITHM = "RSA";
    private static final String RSA_TRANSFORMATION = "RSA/ECB/PKCS1Padding"; // RSA 标准模式
    private static final int RSA_KEY_SIZE = 2048;

    private static final String AES_ALGORITHM = "AES";
    private static final String AES_TRANSFORMATION = "AES/CBC/PKCS5Padding";
    private static final int AES_KEY_SIZE = 128; // 密钥大小
    private static final int IV_SIZE = 16;       // IV 大小，CBC 模式下为 16 字节

    // 静态块：确保加载 Bouncy Castle Provider (如果需要高级特性，如 NoPadding 或特定的算法)
    static {
        if (Security.getProvider("BC") == null) {
            // 注意：如果你的运行环境（如 JDK 9+）已经包含足够的提供者，这行可以移除。
            // Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
        }
    }

    // --- 1. 密钥生成 ---

    /**
     * 生成 RSA 密钥对
     */
    public static KeyPair generateRsaKeyPair() throws NoSuchAlgorithmException {
        // 使用标准 Java Security 提供的 RSA
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance(RSA_ALGORITHM);
        keyGen.initialize(RSA_KEY_SIZE);
        return keyGen.generateKeyPair();
    }

    /**
     * 生成 AES 对称密钥 (128位 / 16字节)
     */
    public static SecretKey generateAesKey() throws NoSuchAlgorithmException {
        KeyGenerator keyGen = KeyGenerator.getInstance(AES_ALGORITHM);
        keyGen.init(AES_KEY_SIZE);
        return keyGen.generateKey();
    }


    // --- 2. RSA 加解密 (用于密钥交换) ---

    /**
     * 使用公钥 RSA 加密数据 (用于加密 AES 密钥)
     * @param data 要加密的字节数组
     * @param publicKey 目标用户的公钥
     * @return Base64 编码的密文
     */
    public static String rsaEncrypt(byte[] data, PublicKey publicKey) throws Exception {
        Cipher cipher = Cipher.getInstance(RSA_TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, publicKey);

        byte[] encryptedBytes = cipher.doFinal(data);
        return Base64.getEncoder().encodeToString(encryptedBytes);
    }

    /**
     * 使用私钥 RSA 解密数据 (用于解密 AES 密钥)
     * @param base64EncryptedData Base64 编码的密文
     * @param privateKey 自己的私钥
     * @return 解密后的字节数组 (即 AES 密钥的字节)
     */
    public static byte[] rsaDecrypt(String base64EncryptedData, PrivateKey privateKey) throws Exception {
        byte[] encryptedData = Base64.getDecoder().decode(base64EncryptedData);

        Cipher cipher = Cipher.getInstance(RSA_TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, privateKey);

        return cipher.doFinal(encryptedData);
    }


    // --- 3. AES 加解密 (用于消息传输) ---

    /**
     * 使用共享 AES 密钥加密消息
     * @param plainText 原始消息内容
     * @param key 共享 AES 密钥
     * @return Base64(IV + 密文) 字符串
     */
    public static String aesEncrypt(String plainText, SecretKey key) throws Exception {
        // 每次加密生成新的随机 IV (初始化向量)
        SecureRandom random = new SecureRandom();
        byte[] iv = new byte[IV_SIZE];
        random.nextBytes(iv);
        IvParameterSpec ivSpec = new IvParameterSpec(iv);

        Cipher cipher = Cipher.getInstance(AES_TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, key, ivSpec);

        // 【使用 StandardCharsets.UTF_8】
        byte[] encryptedData = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

        // 拼接 IV 和密文，并进行 Base64 编码
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        outputStream.write(iv);
        outputStream.write(encryptedData);

        return Base64.getEncoder().encodeToString(outputStream.toByteArray());
    }

    /**
     * 使用共享 AES 密钥解密消息
     * @param base64EncryptedData Base64(IV + 密文) 字符串
     * @param key 共享 AES 密钥
     * @return 解密后的明文
     */
    public static String aesDecrypt(String base64EncryptedData, SecretKey key) throws Exception {
        byte[] encryptedWithIv = Base64.getDecoder().decode(base64EncryptedData);

        // 分割出 IV (前 16 字节) 和密文
        byte[] iv = Arrays.copyOfRange(encryptedWithIv, 0, IV_SIZE);
        byte[] encryptedData = Arrays.copyOfRange(encryptedWithIv, IV_SIZE, encryptedWithIv.length);

        IvParameterSpec ivSpec = new IvParameterSpec(iv);

        Cipher cipher = Cipher.getInstance(AES_TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, key, ivSpec);

        byte[] decryptedData = cipher.doFinal(encryptedData);

        // 【使用 StandardCharsets.UTF_8】
        return new String(decryptedData, StandardCharsets.UTF_8);
    }

    // --- 4. 密钥恢复方法 (为完整性补充) ---

    /**
     * 从 Base64 字符串恢复公钥对象
     */
    public static PublicKey getPublicKey(String base64PublicKey) {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(base64PublicKey);
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(keyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance(RSA_ALGORITHM);
            return keyFactory.generatePublic(keySpec);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            System.err.println("[Security Error] 公钥恢复失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 从 Base64 字符串恢复私钥对象
     */
    public static PrivateKey getPrivateKey(String base64PrivateKey) {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(base64PrivateKey);
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance(RSA_ALGORITHM);
            return keyFactory.generatePrivate(keySpec);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            System.err.println("[Security Error] 私钥恢复失败: " + e.getMessage());
            return null;
        }
    }
}