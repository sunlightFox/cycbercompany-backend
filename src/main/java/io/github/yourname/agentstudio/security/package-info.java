/**
 * 安全上下文模块。
 *
 * <p>这里把 HTTP 请求、节点请求或未来企业认证转换成可信 {@code ActorContext}。
 * 业务模块只相信服务端创建的 ActorContext，不相信客户端或模型传来的 tenant/user/role 字段。
 */
@org.springframework.modulith.ApplicationModule
package io.github.yourname.agentstudio.security;
