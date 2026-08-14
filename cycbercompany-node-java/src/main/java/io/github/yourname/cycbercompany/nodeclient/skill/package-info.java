/**
 * 节点侧 Skill 运行支持模块。
 *
 * <p>后端锁定 Skill Release 后，节点按摘要下载 Bundle、缓存、校验，并在显式启用的安全运行时中执行。
 * 这里的核心目标是可复现和可审计，而不是给脚本无限制的本机权限。
 */
package io.github.yourname.cycbercompany.nodeclient.skill;
