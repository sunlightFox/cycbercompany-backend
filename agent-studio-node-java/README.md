# Agent Studio Java Node

Java 21 节点客户端，用于把本机电脑或服务器注册到 Agent Studio 后端。

当前版本已实现：

- `register`：用后端注册令牌注册节点；
- 本地配置保存；
- WebSocket 连接；
- 心跳；
- 能力上报。

## 注册节点

先在后端创建注册令牌：

```powershell
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/node-registration-tokens -ContentType 'application/json' -Body '{"ttlSeconds":600}'
```

然后注册：

```powershell
..\gradlew.bat :agent-studio-node-java:run --args="register --server http://localhost:8080 --token <registrationToken> --name my-pc"
```

## 启动节点

```powershell
..\gradlew.bat :agent-studio-node-java:run --args="start"
```

## 启用整机/服务器访问

默认节点只允许访问注册时配置的工作区。需要整理桌面、访问其他目录或执行服务器级命令时，注册节点时显式指定 `--access system`：

```powershell
..\gradlew.bat :agent-studio-node-java:run --args="register --server http://localhost:8080 --token <registrationToken> --name my-pc --workspace D:\ai\project --access system"
..\gradlew.bat :agent-studio-node-java:run --args="start"
```

系统模式会额外上报 `system.fs.*` 和 `system.shell.run`。这些工具支持绝对路径，但每次调用仍由服务端审批策略控制；`system.fs.delete` 的递归删除和文件覆盖也必须明确传参。

如果不想使用默认配置路径，可以指定：

```powershell
..\gradlew.bat :agent-studio-node-java:run --args="register --server http://localhost:8080 --token <registrationToken> --name my-pc --config .\node-config.json"
..\gradlew.bat :agent-studio-node-java:run --args="start --config .\node-config.json"
```
