package com.my.chatroom;

import javafx.application.Application;

/**
 * JavaFX 启动器 (MainLauncher)
 * 作用：解决 "缺少 JavaFX 运行时组件" 错误。
 * 这是启动 JavaFX 应用程序的正确入口点。
 */
public class MainLauncher {

    public static void main(String[] args) {
        // 确保数据库被初始化
        // （这一步在 ClientApp 的 main 方法中也有，但放在这里更安全）
        try {
            Class.forName(DatabaseManager.class.getName());
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }

        // 启动 ClientApp
        Application.launch(ClientApp.class, args);
    }
}