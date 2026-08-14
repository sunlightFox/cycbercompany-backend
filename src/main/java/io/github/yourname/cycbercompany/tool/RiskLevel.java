package io.github.yourname.cycbercompany.tool;

/** 工具可能产生的副作用等级，用于决定默认审批策略。 */
public enum RiskLevel {
    /** 只读或几乎没有副作用的操作。 */
    LOW,
    /** 可能改变用户数据，但范围有限的操作。 */
    MEDIUM,
    /** 执行命令、删除文件或控制系统等高影响操作。 */
    HIGH,
    /** Irreversible or security-critical operations that need the strictest policy. */
    CRITICAL
}
