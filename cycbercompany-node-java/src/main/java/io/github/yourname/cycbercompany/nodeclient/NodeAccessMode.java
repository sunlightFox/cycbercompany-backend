package io.github.yourname.cycbercompany.nodeclient;

import java.util.Locale;

/**
 * 声明一个节点进程能够触碰的文件与命令范围。
 *
 * <p>默认的 {@link #WORKSPACE} 只允许访问注册时的工作区；
 * {@link #SYSTEM} 是显式的高权限模式，只有用户明确选择时才启用，并且每个系统工具仍需后端审批。
 */
public enum NodeAccessMode {
    WORKSPACE,
    SYSTEM;

    public static NodeAccessMode from(String value) {
        if (value == null || value.isBlank()) {
            return WORKSPACE;
        }
        try {
            return NodeAccessMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unsupported access mode: " + value + ". Use workspace or system.");
        }
    }

    public boolean permitsSystemAccess() {
        return this == SYSTEM;
    }
}
