package io.github.yourname.cycbercompany.node;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 对语义化节点工具进行服务端参数校验。
 *
 * <p>这里只验证请求是否符合服务端业务规则，不访问节点本机文件系统。文件是否存在、
 * Windows API 是否成功等执行期事实，仍由节点客户端返回真实结果。
 */
public final class NodeToolArgumentValidator {

    private static final Set<String> WALLPAPER_EXTENSIONS = Set.of(".jpg", ".jpeg", ".png", ".bmp");

    private NodeToolArgumentValidator() {
    }

    public static void validate(String toolName, Map<String, Object> arguments) {
        if (!"system.desktop.set_wallpaper".equals(toolName)) {
            return;
        }
        Object value = arguments == null ? null : arguments.get("path");
        if (!(value instanceof String pathText) || pathText.isBlank()) {
            throw new IllegalArgumentException("system.desktop.set_wallpaper requires a non-empty image path.");
        }
        // 服务端可能运行在 Linux，而节点可能是 Windows。因此不能用服务端自身的
        // Path.of() 判断节点路径；这里按 Windows 节点协议校验盘符或 UNC 绝对路径。
        String trimmed = pathText.trim();
        if (!trimmed.matches("^[A-Za-z]:[\\\\/].*") && !trimmed.startsWith("\\\\\\\\")) {
            throw new IllegalArgumentException("Wallpaper path must be an absolute Windows path.");
        }
        String normalized = trimmed.toLowerCase(Locale.ROOT);
        boolean supported = WALLPAPER_EXTENSIONS.stream().anyMatch(normalized::endsWith);
        if (!supported) {
            throw new IllegalArgumentException("Wallpaper must be a JPG, JPEG, PNG, or BMP image.");
        }
    }

}
