/**
 * 模型目录与模型网关模块。
 *
 * <p>这里维护模型 Profile、默认模型、能力标签和 OpenAI-compatible 调用实现。
 * 新手阅读时要区分 Provider、ModelName 和 Profile：运行时固定的是 Profile 快照，
 * 不能在一次 Run 中途被后台配置修改影响。
 */
@org.springframework.modulith.ApplicationModule
package io.github.yourname.agentstudio.model;
