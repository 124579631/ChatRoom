package com.my.chatroom;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.text.Text;

import java.util.Base64; // 【新增】

/**
 * 登录界面的控制器 (LoginController)
 * 【UI 版修改】:
 * 1. 注入 Client 实例。
 * 2. 使用 Task (后台线程) 执行登录。
 * 3. 使用 Platform.runLater (UI 线程) 更新 UI。
 */
public class LoginController {

    @FXML private TextField serverField;
    @FXML private TextField userIdField;
    @FXML private PasswordField passwordField;
    @FXML private Button loginButton;
    @FXML private Button registerButton;
    @FXML private Text statusText;

    // 【新增】持有 Netty 客户端实例
    private Client nettyClient;

    @FXML
    public void initialize() {
        statusText.setText("请输入凭据");
        // 【新增】初始化 Netty 客户端
        this.nettyClient = new Client();
    }

    /**
     * 【核心修改】处理登录按钮点击事件
     */
    @FXML
    private void handleLoginButtonAction() {
        String serverIp = serverField.getText().split(":")[0];
        int serverPort = Integer.parseInt(serverField.getText().split(":")[1]);
        String userId = userIdField.getText();
        String password = passwordField.getText();

        if (serverIp.isEmpty() || userId.isEmpty() || password.isEmpty()) {
            statusText.setText("错误：所有字段均为必填项。");
            return;
        }

        // 禁用按钮，防止重复点击
        loginButton.setDisable(true);
        registerButton.setDisable(true);
        statusText.setText("正在连接服务器...");

        // 【核心】创建后台任务来执行网络操作
        Task<Void> loginTask = new Task<>() {
            @Override
            protected Void call() throws Exception {
                // 1. 在后台线程连接
                nettyClient.connect(serverIp, serverPort,
                        // 2. 提供一个“登录回调”
                        (loginResponse) -> {
                            // 当 Netty (ChatClientHandler) 收到 LoginResponse 时，此回调被触发
                            // 【关键】确保 UI 更新在 JavaFX 主线程上执行
                            Platform.runLater(() -> {
                                handleLoginResponse(loginResponse);
                            });
                        },
                        // 3. 提供一个“消息回调” (登录后才用)
                        (message) -> {
                            Platform.runLater(() -> {
                                // TODO: (第 7 周) 在主聊天窗口显示消息
                                System.out.println("收到消息: " + message.getType());
                            });
                        }
                );

                // 3. (连接后) 在后台线程发送登录请求
                String publicKeyBase64 = Base64.getEncoder().encodeToString(
                        nettyClient.getPrivateKey().getEncoded()
                );
                LoginRequest request = new LoginRequest(userId, password, publicKeyBase64);
                nettyClient.sendMessage(request);

                return null;
            }
        };

        // 任务失败（例如连接超时）
        loginTask.setOnFailed(e -> {
            Platform.runLater(() -> {
                statusText.setText("连接失败: " + loginTask.getException().getMessage());
                loginButton.setDisable(false);
                registerButton.setDisable(false);
            });
        });

        // 启动后台任务
        new Thread(loginTask).start();
    }

    /**
     * 【新增】处理登录响应 (在 UI 线程)
     */
    private void handleLoginResponse(LoginResponse response) {
        if (response.isSuccess()) {
            statusText.setText("登录成功！正在加载主界面...");

            // TODO: (下一步) 在这里关闭登录窗口，并打开主聊天窗口
            System.out.println("登录成功，准备切换窗口。");

            // (临时：测试断开连接)
            // nettyClient.disconnect();

        } else {
            statusText.setText("登录失败: "S + response.getMessage());
            // 重新启用按钮
            loginButton.setDisable(false);
            registerButton.setDisable(false);
            // 断开连接
            nettyClient.disconnect();
            // 重新初始化 Client，因为 EventLoopGroup 已关闭
            this.nettyClient = new Client();
        }
    }

    /**
     * 处理注册按钮点击事件 (无变化)
     */
    @FXML
    private void handleRegisterButtonAction() {
        String userId = userIdField.getText();
        String password = passwordField.getText();

        if (userId.isEmpty() || password.isEmpty()) {
            statusText.setText("错误：注册需要填写用户名和密码。");
            return;
        }

        boolean success = DatabaseManager.registerUser(userId, password);
        if (success) {
            statusText.setText("本地注册成功！请点击 [登录]。");
        } else {
            statusText.setText("本地注册失败：用户已存在。");
        }
    }
}