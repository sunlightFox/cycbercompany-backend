# Agent Studio Java Node

Java 21 节点客户端，用于把本机电脑或服务器注册到 Agent Studio 后端。

当前版本已实现：

- `register`：用后端注册令牌注册节点；
- 本地配置保存；
- WebSocket 连接；
- 心跳；
- 能力上报。

## 重要安全边界

`--workspace` 和默认的 `WORKSPACE` 访问模式只限制节点工具接收的路径与 Shell 的
工作目录（`cwd`），**它不是操作系统沙箱**。Shell 启动的子进程仍然继承节点进程
所属用户的文件权限、环境变量和网络能力，也可能通过命令访问工作区以外的资源。

因此：

- 只在受信任的本机项目中使用普通 workspace Shell；
- 远程节点应使用专门的低权限系统账户，并清理不需要的环境变量；
- 执行不可信仓库、第三方 Skill 脚本或依赖安装时，应放入 Windows Sandbox、容器、
  虚拟机或其他 OS 级隔离环境；
- 不要因为设置了 `--workspace` 就关闭服务端审批或扩大网络权限。

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

个人本地节点建议使用仓库根目录的 Windows 启动器：

```powershell
.\scripts\start-personal-local.ps1 -Workspace D:\work\my-project
```

Windows software management is exposed only in system mode and remains approval gated on the
server. Use `system.privilege.query` first when diagnosing permission-sensitive work. For
uninstalls, prefer `system.uninstall.preflight` followed by `system.uninstall.execute`; the
workflow accepts exact winget package IDs, exact Windows service names, and exact process image
names, then performs a bounded service/process remediation before retrying one exact winget
uninstall. It cannot bypass Windows ACLs, protected services, vendor uninstall UIs, or required
reboots.

该启动器默认通过 UAC 请求当前登录用户的管理员令牌，节点的 `start-local` 也默认使用
`SYSTEM` 能力模式。这里的 `SYSTEM` 只是节点工具范围名称，不是 Windows `LocalSystem`
账户；子进程仍受当前用户的 Windows ACL、服务保护策略和安全产品限制。需要完全绕过
UAC 时可显式使用 `-NoElevation`。

## 启用整机/服务器访问

默认节点只允许访问注册时配置的工作区。需要整理桌面、访问其他目录或执行服务器级命令时，注册节点时显式指定 `--access system`：

```powershell
..\gradlew.bat :agent-studio-node-java:run --args="register --server http://localhost:8080 --token <registrationToken> --name my-pc --workspace D:\ai\project --access system"
..\gradlew.bat :agent-studio-node-java:run --args="start"
```

系统模式会额外上报 `system.fs.*` 和 `system.shell.run`。这些工具支持绝对路径，但每次调用仍由服务端审批策略控制；`system.fs.delete` 的递归删除和文件覆盖也必须明确传参。

`SYSTEM` 模式拥有节点进程所属用户可以访问的整机范围，只适合用户明确选择的个人
电脑管理任务。它不能作为运行未知代码或未知 Skill 的隔离环境。

如果不想使用默认配置路径，可以指定：

```powershell
..\gradlew.bat :agent-studio-node-java:run --args="register --server http://localhost:8080 --token <registrationToken> --name my-pc --config .\node-config.json"
..\gradlew.bat :agent-studio-node-java:run --args="start --config .\node-config.json"
```
