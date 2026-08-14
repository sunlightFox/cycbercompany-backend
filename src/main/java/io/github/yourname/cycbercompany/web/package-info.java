/**
 * Web API 边界模块。
 *
 * <p>Controller 负责 HTTP 路由、参数校验、上传下载、SSE 连接和异常映射。它应该很薄：
 * 只把请求转换为命令对象并交给业务服务，不在 Controller 中实现模型调用、节点调用或检索逻辑。
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"security", "conversation", "agent", "model", "knowledge", "tool", "orchestration", "skill", "mcp", "node", "artifact"})
package io.github.yourname.cycbercompany.web;
