package com.my.chatroom;

import com.google.gson.Gson;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class JsonToMessageDecoder extends ByteToMessageDecoder {

    // 使用我们配置了 TypeAdapter 的 Gson 实例
    private static final Gson GSON = MessageTypeAdapter.createGson();

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        // LengthFieldBasedFrameDecoder 已经确保这是一个完整的消息帧

        int length = in.readableBytes();
        byte[] bytes = new byte[length];
        in.readBytes(bytes);

        String json = new String(bytes, StandardCharsets.UTF_8);

        // 使用配置好的 Gson 反序列化为 Message 基类
        // TypeAdapter 会自动处理子类转换
        Message msg = GSON.fromJson(json, Message.class);

        out.add(msg);
    }
}