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

如果不想使用默认配置路径，可以指定：

```powershell
..\gradlew.bat :agent-studio-node-java:run --args="register --server http://localhost:8080 --token <registrationToken> --name my-pc --config .\node-config.json"
..\gradlew.bat :agent-studio-node-java:run --args="start --config .\node-config.json"
```
