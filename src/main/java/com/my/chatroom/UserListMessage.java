package com.my.chatroom;

import java.util.List;

public class UserListMessage extends Message {

    private List<String> onlineUsers;

    public UserListMessage(List<String> onlineUsers) {
        super(MessageType.USER_LIST_UPDATE);
        this.onlineUsers = onlineUsers;
    }

    // GSON 需要一个无参构造函数
    public UserListMessage() {
        super(MessageType.USER_LIST_UPDATE);
    }

    public List<String> getOnlineUsers() {
        return onlineUsers;
    }

    // 此方法在实际使用中可能用不到，但为了完整性可以保留
    public void setOnlineUsers(List<String> onlineUsers) {
        this.onlineUsers = onlineUsers;
    }
}