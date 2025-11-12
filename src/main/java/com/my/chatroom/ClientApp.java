package com.my.chatroom;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.net.URL;

/**
 * JavaFX 客户端启动入口 (ClientApp)
 * 作用：加载 FXML 布局和 CSS 样式，显示登录窗口。
 */
public class ClientApp extends Application {

    @Override
    public void start(Stage primaryStage) throws Exception {
        // 1. 加载 FXML 布局文件 (登录界面)
        // 注意：确保 FXML 文件在正确的资源路径下
        URL fxmlLocation = getClass().getResource("/Login.fxml");
        if (fxmlLocation == null) {
            System.err.println("❌ 错误：无法加载 FXML 文件。请检查路径。");
            return;
        }
        FXMLLoader loader = new FXMLLoader(fxmlLocation);
        Parent root = loader.load();

        // 2. 加载 CSS 样式表 (暗色黑客风)
        URL cssLocation = getClass().getResource("/style.css");
        if (cssLocation != null) {
            root.getStylesheets().add(cssLocation.toExternalForm());
        } else {
            System.out.println("⚠️ 警告：未找到 style.css，将使用默认样式。");
        }

        // 3. 创建并显示场景
        primaryStage.setTitle("安全聊天室 - 登录");
        primaryStage.setScene(new Scene(root, 350, 400));
        primaryStage.setResizable(false);
        primaryStage.show();
    }

    public static void main(String[] args) {
        // 确保数据库被初始化
        try {
            Class.forName(DatabaseManager.class.getName());
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }

        // 启动 JavaFX 应用程序
        launch(args);
    }
}