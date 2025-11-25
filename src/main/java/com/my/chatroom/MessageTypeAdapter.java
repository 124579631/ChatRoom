package com.my.chatroom;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;

/**
 * 消息类型适配器 (MessageTypeAdapter)
 * 作用：解决 Gson 在反序列化时不知道如何将 Message 基类对象转换为正确的子类对象的问题。
 * 【已修改】：注册了 BurnAfterReadMessage 类。
 */
public class MessageTypeAdapter extends TypeAdapter<Message> {

    // 使用 ThreadLocal 避免 Gson 实例被多线程共享时出现问题
    private final ThreadLocal<Gson> localGson = ThreadLocal.withInitial(() -> new Gson());

    // 核心：用于注册所有子类的 Gson 实例
    private static Gson registrationGson;

    /**
     * 创建一个包含所有 TypeAdapter 注册的 Gson 实例
     */
    public static Gson createGson() {
        if (registrationGson == null) {
            GsonBuilder builder = new GsonBuilder();
            MessageTypeAdapter adapter = new MessageTypeAdapter();

            // 注册 TypeAdapter
            registrationGson = builder
                    .registerTypeAdapter(Message.class, adapter)
                    .registerTypeHierarchyAdapter(Message.class, adapter)
                    // 注册所有协议子类
                    .registerTypeAdapter(TextMessage.class, adapter)
                    .registerTypeAdapter(LoginRequest.class, adapter)
                    .registerTypeAdapter(LoginResponse.class, adapter)
                    .registerTypeAdapter(KeyExchangeRequest.class, adapter)
                    .registerTypeAdapter(KeyExchangeResponse.class, adapter)
                    .registerTypeAdapter(AESKeyExchangeMessage.class, adapter)
                    .registerTypeAdapter(UserListMessage.class, adapter)
                    // 阅后即焚消息
                    .registerTypeAdapter(BurnAfterReadMessage.class, adapter)
                    .registerTypeAdapter(ImageMessage.class, adapter)
                    .create();
        }
        return registrationGson;
    }

    @Override
    public void write(JsonWriter out, Message value) throws IOException {
        // 使用 ThreadLocal 中的 Gson 实例进行序列化
        localGson.get().toJson(value, value.getClass(), out);
    }

    @Override
    public Message read(JsonReader in) throws IOException {
        JsonObject tree = localGson.get().fromJson(in, JsonObject.class);

        if (!tree.has("type")) {
            return null; // 缺少类型字段
        }

        // 1. 获取类型字符串并转换为枚举
        String typeString = tree.get("type").getAsString();
        Message.MessageType type;
        try {
            type = Message.MessageType.valueOf(typeString);
        } catch (IllegalArgumentException e) {
            return null; // 未知的消息类型
        }

        // 2. 根据类型选择目标子类
        Class<? extends Message> targetClass;

        // ** 【子类注册中心 - 包含所有协议】 **
        if (type == Message.MessageType.TEXT_MESSAGE_ENCRYPTED) {
            targetClass = TextMessage.class;
        }
        else if (type == Message.MessageType.LOGIN_REQUEST) {
            targetClass = LoginRequest.class;
        }
        else if (type == Message.MessageType.LOGIN_RESPONSE) {
            targetClass = LoginResponse.class;
        }
        // 【新增处理】阅后即焚消息
        else if (type == Message.MessageType.BURN_AFTER_READ) {
            targetClass = BurnAfterReadMessage.class;
        }
        else if (type == Message.MessageType.KEY_EXCHANGE_REQUEST) {
            targetClass = KeyExchangeRequest.class;
        }
        else if (type == Message.MessageType.KEY_EXCHANGE_RESPONSE) {
            targetClass = KeyExchangeResponse.class;
        }
        else if (type == Message.MessageType.AES_KEY_EXCHANGE) {
            targetClass = AESKeyExchangeMessage.class;
        }
        else if (type == Message.MessageType.USER_LIST_UPDATE) {
            targetClass = UserListMessage.class;
        }
        else if (type == Message.MessageType.IMAGE_MESSAGE) {
            targetClass = ImageMessage.class;
        }
        else {
            // 如果不是已知的子类，就反序列化为 Message 基类
            targetClass = Message.class;
        }

        // 3. 将 JsonObject 转换为正确的子类对象
        return localGson.get().fromJson(tree, targetClass);
    }
}