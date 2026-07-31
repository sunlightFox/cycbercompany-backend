# 远程节点执行器实现计划

这份清单对应 `docs/node-execution-architecture.md`，用于后续直接按阶段开发。

## N1：节点注册与在线状态

后端交付：

- `node` 业务模块；
- 节点注册令牌；
- 节点注册接口；
- 节点列表接口；
- 节点详情接口；
- 节点启用/禁用；
- WebSocket 节点通道；
- 心跳与在线/离线状态。

建议接口：

```text
POST   /api/v1/node-registration-tokens
POST   /api/v1/nodes/register
GET    /api/v1/nodes
GET    /api/v1/nodes/{id}
PATCH  /api/v1/nodes/{id}
DELETE /api/v1/nodes/{id}
GET    /api/v1/node-channel
```

验收：

- 可以创建短期注册 token；
- 节点可以注册并获得 nodeId；
- 节点 WebSocket 连上后显示在线；
- 节点断开后自动变离线；
- 禁用节点后不能执行任何工具。

## N2：节点能力和工具管理

后端交付：

- 节点能力表；
- 节点工具表；
- 工具启用/禁用；
- 工具风险等级；
- 工具输入 schema；
- 聚合到 `/api/v1/tools`。

验收：

- 同一个节点的具体工具可以单独启用/禁用；
- 不同节点的同名工具不会冲突；
- Agent 只看到启用后的节点工具。

## N3：文件系统、Shell 和 Git

后端交付：

- 工具调用下发；
- 工具调用结果回传；
- 超时；
- 取消；
- 审计日志；
- 路径白名单；
- 高风险命令审批钩子。

节点交付：

- `fs.list`
- `fs.read`
- `fs.write`
- `fs.apply_patch`
- `shell.run`
- `git.status`
- `git.diff`

验收：

- Agent 能读取指定项目文件；
- Agent 能修改代码；
- Agent 能运行测试；
- 越权路径被拒绝；
- 高风险命令不会静默执行。

## N4：Playwright 浏览器节点工具

节点交付：

- `browser.open`
- `browser.snapshot`
- `browser.screenshot`
- `browser.click`
- `browser.type`

验收：

- Agent 能打开本地前端；
- Agent 能截图；
- Agent 能根据页面结构点击和输入；
- 页面状态能回传给模型继续推理。

## N5：Agent 编程闭环

目标：

让 Agent 可以在节点上完成完整开发循环：

```text
读代码 -> 修改代码 -> 跑测试 -> 看错误 -> 再修改 -> 看 diff -> 总结
```

验收：

- 一个 Run 内可以多轮调用节点工具；
- 工具调用全部进入 Run Event；
- 用户可以回放执行过程；
- 失败时能看到具体命令、退出码、输出摘要。
