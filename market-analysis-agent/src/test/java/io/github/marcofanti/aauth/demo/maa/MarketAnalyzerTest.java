package io.github.marcofanti.aauth.demo.maa;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MarketAnalyzerTest {

    private final MarketAnalyzer analyzer = new MarketAnalyzer();

    @Test
    void defaultsToDemandAnalysis() {
        String report = analyzer.analyze("analyze laptop needs");

        assertThat(report).contains("Laptop Demand").contains("Total demand");
    }

    @Test
    void trendKeywordSelectsTrendReport() {
        String report = analyzer.analyze("forecast market trends");

        assertThat(report).contains("Market Trends").doesNotContain("Laptop Demand");
    }

    @Test
    void patternKeywordSelectsPatternReport() {
        String report = analyzer.analyze("model demand patterns");

        assertThat(report).contains("Demand Patterns").contains("MacBook Pro");
    }

    @Test
    void comprehensiveIncludesAllSections() {
        String report = analyzer.analyze("comprehensive market analysis");

        assertThat(report).contains("Laptop Demand").contains("Market Trends").contains("Demand Patterns");
    }

    @Test
    void horizonScalesWithKeywords() {
        assertThat(analyzer.analyze("demand for the year")).contains("next 12 months");
        assertThat(analyzer.analyze("demand this quarter")).contains("next 3 months");
        assertThat(analyzer.analyze("demand")).contains("next 6 months");
    }

    @Test
    void nullAndEmptyInputAreSafe() {
        assertThat(analyzer.analyze(null)).contains("Laptop Demand");
        assertThat(analyzer.analyze("")).contains("Laptop Demand");
    }

    @Test
    void demandNumbersAreConsistent() {
        String report = analyzer.analyze("demand this quarter");

        // 51 planned hires over 6 months -> 25 for 3 months; fleet 820 / 36-month cycle -> 68 for 3 months.
        assertThat(report).contains("| New hires | 25 |").contains("| Fleet refresh | 68 |");
    }
}
