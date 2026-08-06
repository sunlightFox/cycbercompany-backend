/**
 * 节点侧 Artifact 上传模块。
 *
 * <p>节点在本地生成截图、Trace 或诊断文件后，通过这里上传到后端 Artifact API。
 * WebSocket 结果只返回摘要和下载引用，避免把本机临时路径泄露给模型或前端。
 */
package io.github.yourname.agentstudio.nodeclient.artifact;
