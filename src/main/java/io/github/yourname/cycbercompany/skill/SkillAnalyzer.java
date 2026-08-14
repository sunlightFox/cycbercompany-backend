package io.github.yourname.cycbercompany.skill;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * 对不可变 Skill Release 做纯静态分析。
 *
 * <p>分析器不会运行脚本、安装依赖或访问网络。无法确认的声明会变成警告或兼容错误，绝不因
 * “看起来像某个工具”而授予权限。
 */
@Service
public class SkillAnalyzer {

    private static final Pattern FRONT_MATTER = Pattern.compile(
            "(?s)\\A---[ \\t]*\\R(.*?)\\R---[ \\t]*(?:\\R|$)");

    private final SkillCatalog catalog;
    private final ObjectMapper yaml = new YAMLMapper();

    public SkillAnalyzer(SkillCatalog catalog) {
        this.catalog = catalog;
    }

    public List<SkillAnalysis> analyze(List<SkillRunBinding> bindings) {
        if (bindings == null || bindings.isEmpty()) {
            return List.of();
        }
        return bindings.stream().map(this::analyze).toList();
    }

    private SkillAnalysis analyze(SkillRunBinding binding) {
        SkillCatalog.ReleaseSnapshot release = catalog.readReleaseSnapshot(binding);
        Map<String, Object> frontmatter = frontmatter(release.markdown(), binding.skillId());
        List<String> declaredTools = declaredTools(frontmatter);
        List<String> requiredTools = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (String declared : declaredTools) {
            SkillToolAlias.resolve(declared).ifPresentOrElse(
                    requiredTools::add,
                    () -> warnings.add("Unknown tool alias: " + declared));
        }

        List<String> scripts = release.files().stream()
                .filter(path -> path.startsWith("scripts/"))
                .toList();
        List<String> resources = release.files().stream()
                .filter(path -> path.startsWith("references/")
                        || path.startsWith("templates/")
                        || path.startsWith("assets/"))
                .toList();
        List<SkillAnalysis.RuntimeRequirement> runtimes = runtimes(frontmatter, scripts);
        List<String> features = textList(nested(frontmatter, "requirements", "features"));
        if (!scripts.isEmpty()) {
            features = new ArrayList<>(features);
            features.add("skill.script.runtime.v1");
            // Only runtimes supported by the node-side Docker runner are eligible for execution.
            for (String script : scripts) {
                String feature = scriptFeature(script);
                if (feature == null) {
                    warnings.add("Unsupported script runtime: " + script);
                } else {
                    features.add(feature);
                }
            }
        }
        String network = text(nested(frontmatter, "requirements", "network"));
        int level = scripts.isEmpty() ? (resources.isEmpty() ? 1 : 2) : 3;
        return new SkillAnalysis(
                binding.skillId(),
                level,
                distinct(declaredTools),
                distinct(requiredTools),
                runtimes,
                distinct(features),
                network,
                resources,
                scripts,
                warnings);
    }

    private Map<String, Object> frontmatter(String markdown, String skillId) {
        Matcher matcher = FRONT_MATTER.matcher(markdown == null ? "" : markdown);
        if (!matcher.find()) {
            return Map.of();
        }
        try {
            Map<String, Object> values = yaml.readValue(
                    matcher.group(1), new TypeReference<LinkedHashMap<String, Object>>() { });
            return values == null ? Map.of() : values;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid Skill frontmatter in immutable release " + skillId + ".", ex);
        }
    }

    private static List<String> declaredTools(Map<String, Object> fields) {
        List<String> result = new ArrayList<>();
        result.addAll(textList(fields.get("allowed-tools")));
        result.addAll(textList(fields.get("tools")));
        result.addAll(textList(nested(fields, "requirements", "tools")));
        return distinct(result);
    }

    private static List<SkillAnalysis.RuntimeRequirement> runtimes(
            Map<String, Object> fields,
            List<String> scripts) {
        Map<String, SkillAnalysis.RuntimeRequirement> result = new LinkedHashMap<>();
        Object declared = nested(fields, "requirements", "runtimes");
        if (declared instanceof List<?> values) {
            for (Object value : values) {
                if (value instanceof Map<?, ?> runtime) {
                    String name = text(runtime.get("name"));
                    if (name != null) {
                        result.put(name.toLowerCase(Locale.ROOT), new SkillAnalysis.RuntimeRequirement(
                                name.toLowerCase(Locale.ROOT), text(runtime.get("version")), "frontmatter"));
                    }
                } else if (value != null) {
                    addRuntimeText(result, value.toString(), "frontmatter");
                }
            }
        } else if (declared != null) {
            for (String value : textList(declared)) {
                addRuntimeText(result, value, "frontmatter");
            }
        }
        for (String script : scripts) {
            String lower = script.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".py")) addRuntime(result, "python", null, "script extension");
            if (lower.endsWith(".js") || lower.endsWith(".mjs")) {
                addRuntime(result, "node", null, "script extension");
            }
            if (lower.endsWith(".sh")) addRuntime(result, "shell", null, "script extension");
        }
        return List.copyOf(result.values());
    }

    private static void addRuntimeText(
            Map<String, SkillAnalysis.RuntimeRequirement> result,
            String declaration,
            String source) {
        String[] pieces = declaration.trim().split("\\s+", 2);
        if (pieces.length > 0 && !pieces[0].isBlank()) {
            addRuntime(result, pieces[0].toLowerCase(Locale.ROOT), pieces.length == 2 ? pieces[1] : null, source);
        }
    }

    private static void addRuntime(
            Map<String, SkillAnalysis.RuntimeRequirement> result,
            String name,
            String constraint,
            String source) {
        result.putIfAbsent(name, new SkillAnalysis.RuntimeRequirement(name, constraint, source));
    }

    private static String scriptFeature(String script) {
        String lower = script.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".py")) return "skill.script.python.v1";
        if (lower.endsWith(".js") || lower.endsWith(".mjs")) return "skill.script.node.v1";
        if (lower.endsWith(".sh")) return "skill.script.shell.v1";
        return null;
    }

    private static Object nested(Map<String, Object> root, String objectName, String fieldName) {
        Object nested = root.get(objectName);
        return nested instanceof Map<?, ?> map ? map.get(fieldName) : null;
    }

    private static List<String> textList(Object value) {
        if (value == null) return List.of();
        if (value instanceof List<?> values) {
            return values.stream().filter(java.util.Objects::nonNull).map(Object::toString)
                    .map(String::trim).filter(text -> !text.isBlank()).toList();
        }
        String text = value.toString().trim();
        if (text.isBlank()) return List.of();
        return java.util.Arrays.stream(text.split("[,\\s]+"))
                .map(String::trim).filter(item -> !item.isBlank()).toList();
    }

    private static String text(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString().trim();
    }

    private static List<String> distinct(List<String> values) {
        return List.copyOf(new LinkedHashSet<>(values));
    }
}
