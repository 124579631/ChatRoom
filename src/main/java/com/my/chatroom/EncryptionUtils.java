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
import java.nio.charset.StandardCharsets;

/**
 * 加密工具类 - 安全升级版
 */
public class EncryptionUtils {

    // ... (原有的常量和静态块保持不变) ...
    private static final String RSA_ALGORITHM = "RSA";
    private static final String RSA_TRANSFORMATION = "RSA/ECB/PKCS1Padding";
    private static final int RSA_KEY_SIZE = 2048;
    private static final String AES_ALGORITHM = "AES";
    private static final String AES_TRANSFORMATION = "AES/CBC/PKCS5Padding";
    private static final int AES_KEY_SIZE = 128;
    private static final int IV_SIZE = 16;

    static {
        if (Security.getProvider("BC") == null) {
            // Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
        }
    }

    // ... (原有的 generateRsaKeyPair, generateAesKey, rsaEncrypt, rsaDecrypt 保持不变) ...
    public static KeyPair generateRsaKeyPair() throws NoSuchAlgorithmException {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance(RSA_ALGORITHM);
        keyGen.initialize(RSA_KEY_SIZE);
        return keyGen.generateKeyPair();
    }

    public static SecretKey generateAesKey() throws NoSuchAlgorithmException {
        KeyGenerator keyGen = KeyGenerator.getInstance(AES_ALGORITHM);
        keyGen.init(AES_KEY_SIZE);
        return keyGen.generateKey();
    }

    public static String rsaEncrypt(byte[] data, PublicKey publicKey) throws Exception {
        Cipher cipher = Cipher.getInstance(RSA_TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, publicKey);
        return Base64.getEncoder().encodeToString(cipher.doFinal(data));
    }

    public static byte[] rsaDecrypt(String base64EncryptedData, PrivateKey privateKey) throws Exception {
        Cipher cipher = Cipher.getInstance(RSA_TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, privateKey);
        return cipher.doFinal(Base64.getDecoder().decode(base64EncryptedData));
    }

    // ... (原有的 aesEncrypt, aesDecrypt 保持不变) ...
    public static String aesEncrypt(String plainText, SecretKey key) throws Exception {
        SecureRandom random = new SecureRandom();
        byte[] iv = new byte[IV_SIZE];
        random.nextBytes(iv);
        IvParameterSpec ivSpec = new IvParameterSpec(iv);
        Cipher cipher = Cipher.getInstance(AES_TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, key, ivSpec);
        byte[] encryptedData = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        outputStream.write(iv);
        outputStream.write(encryptedData);
        return Base64.getEncoder().encodeToString(outputStream.toByteArray());
    }

    public static String aesDecrypt(String base64EncryptedData, SecretKey key) throws Exception {
        byte[] encryptedWithIv = Base64.getDecoder().decode(base64EncryptedData);
        byte[] iv = Arrays.copyOfRange(encryptedWithIv, 0, IV_SIZE);
        byte[] encryptedData = Arrays.copyOfRange(encryptedWithIv, IV_SIZE, encryptedWithIv.length);
        IvParameterSpec ivSpec = new IvParameterSpec(iv);
        Cipher cipher = Cipher.getInstance(AES_TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, key, ivSpec);
        return new String(cipher.doFinal(encryptedData), StandardCharsets.UTF_8);
    }

    // --- 【新增】安全辅助方法 ---

    /**
     * 从密码派生 AES 密钥 (用于保护本地密钥库)
     * 使用 SHA-256 哈希的前 128 位作为 AES 密钥。
     * (在更严格的场景下应使用 PBKDF2，但此处 SHA-256 已足够演示原理)
     */
    public static SecretKey deriveKeyFromPassword(String password) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(password.getBytes(StandardCharsets.UTF_8));
        // 截取前 16 字节 (128位) 作为 AES 密钥
        byte[] keyBytes = Arrays.copyOf(hash, 16);
        return new SecretKeySpec(keyBytes, AES_ALGORITHM);
    }

    public static PublicKey getPublicKey(String base64PublicKey) {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(base64PublicKey);
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(keyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance(RSA_ALGORITHM);
            return keyFactory.generatePublic(keySpec);
        } catch (Exception e) { return null; }
    }

    public static PrivateKey getPrivateKey(String base64PrivateKey) {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(base64PrivateKey);
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance(RSA_ALGORITHM);
            return keyFactory.generatePrivate(keySpec);
        } catch (Exception e) { return null; }
    }
}