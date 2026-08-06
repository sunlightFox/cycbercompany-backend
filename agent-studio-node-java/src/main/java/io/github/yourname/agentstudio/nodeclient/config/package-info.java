/**
 * 节点本地配置模块。
 *
 * <p>保存 serverUrl、nodeId、nodeSecret、workspace 和访问模式等信息。Windows 下可使用
 * DPAPI 保护敏感配置；测试或非 Windows 环境可回退到明文保护器。
 */
package io.github.yourname.agentstudio.nodeclient.config;
