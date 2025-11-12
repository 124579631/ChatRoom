package com.my.chatroom;

import com.google.gson.Gson;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import java.nio.charset.StandardCharsets;

public class MessageToJsonEncoder extends MessageToByteEncoder<Message> {

    // 使用我们配置了 TypeAdapter 的 Gson 实例
    private static final Gson GSON = MessageTypeAdapter.createGson();

    @Override
    protected void encode(ChannelHandlerContext ctx, Message msg, ByteBuf out) throws Exception {
        String json = GSON.toJson(msg);
        out.writeBytes(json.getBytes(StandardCharsets.UTF_8));
    }
}