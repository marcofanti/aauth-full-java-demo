package io.github.marcofanti.aauth.demo.sca;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.marcofanti.aauth.demo.a2a.A2aClientException;
import org.junit.jupiter.api.Test;

class SupplyChainOptimizerTest {

    private static final MarketAnalysisGateway UNUSED_GATEWAY = requestText -> {
        throw new AssertionError("Gateway must not be called for this request");
    };

    @Test
    void laptopKeywordSetsFocusArea() {
        String report = new SupplyChainOptimizer(UNUSED_GATEWAY).optimize("optimize laptop supply chain");

        assertThat(report).contains("Focus area: Laptop fleet procurement");
    }

    @Test
    void defaultsToGeneralProcurementAndBalancedGoal() {
        String report = new SupplyChainOptimizer(UNUSED_GATEWAY).optimize("optimize things");

        assertThat(report)
                .contains("Focus area: General procurement")
                .contains("Optimization goal: Balanced cost and availability");
    }

    @Test
    void costKeywordSetsCostGoal() {
        String report = new SupplyChainOptimizer(UNUSED_GATEWAY).optimize("reduce budget for laptops");

        assertThat(report).contains("Optimization goal: Cost reduction");
    }

    @Test
    void urgencyKeywordSetsSpeedGoal() {
        String report = new SupplyChainOptimizer(UNUSED_GATEWAY).optimize("urgent laptop order");

        assertThat(report).contains("Optimization goal: Delivery speed");
    }

    @Test
    void inventoryKeywordAddsBufferReview() {
        String report = new SupplyChainOptimizer(UNUSED_GATEWAY).optimize("check laptop inventory levels");

        assertThat(report).contains("Buffer review requested");
    }

    @Test
    void reportAlwaysContainsPoliciesAndRecommendations() {
        String report = new SupplyChainOptimizer(UNUSED_GATEWAY).optimize(null);

        assertThat(report)
                .contains("## Applied Business Policies")
                .contains("## Recommendations")
                .contains("CFO approval")
                .doesNotContain("## Market Analysis");
    }

    @Test
    void marketAnalysisTriggerSplicesDownstreamReport() {
        MarketAnalysisGateway gateway = requestText -> "# Market Analysis Report\n\ndownstream content";
        String report = new SupplyChainOptimizer(gateway).optimize("optimize laptops and perform market analysis");

        assertThat(report).contains("## Market Analysis").contains("downstream content");
    }

    @Test
    void marketAnalysisFailureIsNonFatal() {
        MarketAnalysisGateway gateway = requestText -> {
            throw new A2aClientException("connection refused");
        };
        String report = new SupplyChainOptimizer(gateway).optimize("optimize laptops and perform market analysis");

        assertThat(report)
                .contains("# Supply Chain Optimization Report")
                .contains("Market analysis unavailable: connection refused");
    }
}
