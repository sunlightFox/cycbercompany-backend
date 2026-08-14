/**
 * 远程/本地节点控制模块。
 *
 * <p>节点是实际执行文件、Shell、Git、浏览器、桌面动作的伴随进程。后端通过本模块完成
 * 注册、认证、心跳、能力上报、审批、调用审计和断线对账。它是安全边界，不能把节点
 * 上报的能力直接当成授权来源。
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"security", "tool"})
package io.github.yourname.cycbercompany.node;
