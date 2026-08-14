/**
 * Skill 能力包模块。
 *
 * <p>Skill 是声明式指令包：它可以提供 {@code SKILL.md}、引用资料、模板和受控脚本入口。
 * 安装 Skill 不等于执行脚本；创建 Run 时会把选中的 Skill 固定成不可变 Release 快照，
 * 以保证旧 Run 不受后续升级或卸载影响。
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"config", "security", "tool", "node", "agent", "knowledge"})
package io.github.yourname.cycbercompany.skill;
