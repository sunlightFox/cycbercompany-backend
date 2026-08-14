package io.github.yourname.cycbercompany.tool;

import java.util.List;

public record WebSearchTrace(
        List<WebSearchProviderTrace> providers,
        int duplicateCount,
        int domainLimitedCount,
        int uniqueDomainCount,
        int freshnessFilteredCount,
        int pagesRead,
        int verifiedPages) {

    public WebSearchTrace(
            List<WebSearchProviderTrace> providers,
            int duplicateCount,
            int domainLimitedCount,
            int uniqueDomainCount) {
        this(providers, duplicateCount, domainLimitedCount, uniqueDomainCount, 0, 0, 0);
    }

    public String summary() {
        String providersSummary = providers.stream()
                .map(item -> item.sourceId() + ":" + item.status() + ":" + item.resultCount())
                .reduce((left, right) -> left + "," + right)
                .orElse("none");
        return "providers=" + providersSummary
                + ", duplicates=" + duplicateCount
                + ", domainLimited=" + domainLimitedCount
                + ", domains=" + uniqueDomainCount
                + ", freshnessFiltered=" + freshnessFilteredCount
                + ", pagesRead=" + pagesRead
                + ", verified=" + verifiedPages;
    }
}
