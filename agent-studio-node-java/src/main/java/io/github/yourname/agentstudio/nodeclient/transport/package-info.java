/**
 * WebSocket 传输模块。
 *
 * <p>负责节点主动连接后端、发送心跳、接收 tool.invoke、回传 tool.result，并在断线重连后配合
 * Journal 做调用状态对账。传输层不判断业务权限，权限由后端控制面决定。
 */
package io.github.yourname.agentstudio.nodeclient.transport;
