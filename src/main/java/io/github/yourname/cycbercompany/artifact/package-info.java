/**
 * Artifact 文件模块。
 *
 * <p>Artifact 是节点或后端生成的可下载成果，例如浏览器截图、Playwright Trace、
 * 诊断文件等。它只负责文件落盘、摘要、下载权限和租户隔离，不负责决定什么时候
 * 需要截图或如何解读截图内容。
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"config", "security"})
package io.github.yourname.cycbercompany.artifact;
