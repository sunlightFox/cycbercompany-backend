package io.github.yourname.agentstudio.node;

import java.util.List;

/**
 * 编码任务的可解释质量评分。
 *
 * <p>分数只反映工具调用留下的交付证据是否完整，不能证明业务需求一定正确。
 * 它的作用是帮助新手发现“改了文件却没有测试”这类常见交付缺口。
 */
public record CodingRunQualityView(
        String runId,
        int score,
        String grade,
        List<CodingQualityCheckView> checks,
        List<String> recommendations) {

    /** 单个评分项及其分值来源，页面可以直接逐项展示，无需猜测扣分原因。 */
    public record CodingQualityCheckView(String name, int earnedPoints, int maximumPoints, boolean passed, String explanation) {
    }
}
