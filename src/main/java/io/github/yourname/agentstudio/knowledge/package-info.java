/**
 * 知识库模块。
 *
 * <p>负责知识库、文档摄取、切块、Embedding、关键词/向量检索和引用证据返回。
 * 注意：本模块只返回证据 {@code EvidenceBundle}，不负责让大模型生成最终回答。
 * 这样 ACL、引用和检索策略能集中在一个地方维护。
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"security", "config", "model"})
package io.github.yourname.agentstudio.knowledge;
