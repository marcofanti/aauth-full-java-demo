package io.github.marcofanti.aauth.demo.sca;

import io.github.marcofanti.aauth.demo.a2a.A2aClientException;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Keyword-driven supply chain optimization over {@link BusinessPolicies}, rendered as Markdown.
 * When the request contains {@value #MARKET_ANALYSIS_TRIGGER}, delegates a second A2A hop to the
 * market-analysis agent; that hop failing is non-fatal.
 */
@Component
public class SupplyChainOptimizer {

    public static final String MARKET_ANALYSIS_TRIGGER = "perform market analysis";

    private final MarketAnalysisGateway marketAnalysis;

    public SupplyChainOptimizer(MarketAnalysisGateway marketAnalysis) {
        this.marketAnalysis = marketAnalysis;
    }

    public String optimize(String requestText) {
        String request = requestText == null ? "" : requestText.toLowerCase(Locale.ROOT);
        StringBuilder report = new StringBuilder("# Supply Chain Optimization Report\n\n");
        report.append(summarySection(request));
        report.append(policiesSection(request));
        report.append(recommendationsSection());
        if (request.contains(MARKET_ANALYSIS_TRIGGER)) {
            report.append("## Market Analysis\n\n").append(marketAnalysisSection(requestText));
        }
        return report.toString();
    }

    private static String summarySection(String request) {
        String focus = request.contains("laptop") || request.contains("hardware")
                ? "Laptop fleet procurement"
                : "General procurement";
        String goal = optimizationGoal(request);
        return "## Request Summary\n\n- Focus area: " + focus + "\n- Optimization goal: " + goal + "\n\n";
    }

    private static String optimizationGoal(String request) {
        if (request.contains("cost") || request.contains("budget")) {
            return "Cost reduction";
        }
        if (request.contains("urgent") || request.contains("speed") || request.contains("fast")) {
            return "Delivery speed";
        }
        return "Balanced cost and availability";
    }

    private static String policiesSection(String request) {
        StringBuilder section = new StringBuilder("## Applied Business Policies\n\n");
        section.append("- Inventory buffer: ")
                .append(BusinessPolicies.INVENTORY_BUFFER_MONTHS)
                .append(" months of projected demand\n");
        section.append("- Orders above $")
                .append(BusinessPolicies.CFO_APPROVAL_THRESHOLD_USD)
                .append(" require CFO approval\n");
        section.append("- Single orders capped at $")
                .append(BusinessPolicies.MAX_SINGLE_ORDER_USD)
                .append(" (quarterly budget $")
                .append(BusinessPolicies.QUARTERLY_BUDGET_USD)
                .append(")\n");
        section.append("- Preferred vendors: tier 1 ")
                .append(BusinessPolicies.TIER1_VENDORS)
                .append(", tier 2 ")
                .append(BusinessPolicies.TIER2_VENDORS)
                .append("\n");
        if (request.contains("inventory") || request.contains("stock")) {
            section.append("- Buffer review requested: validating stock against minimum levels\n");
        }
        return section.append('\n').toString();
    }

    private static String recommendationsSection() {
        StringBuilder section = new StringBuilder("## Recommendations\n\n");
        int index = 1;
        section.append(index++)
                .append(". Source from tier-1 vendors first (")
                .append(String.join(", ", BusinessPolicies.TIER1_VENDORS))
                .append("); fall back to tier 2 only on lead-time risk.\n");
        section.append(index++)
                .append(". Split any order above $")
                .append(BusinessPolicies.MAX_SINGLE_ORDER_USD)
                .append(" into staged purchases to stay within the single-order cap.\n");
        section.append(index++)
                .append(". Route orders above $")
                .append(BusinessPolicies.CFO_APPROVAL_THRESHOLD_USD)
                .append(" to CFO approval before committing.\n");
        section.append(index).append(". Keep minimum stock levels: ");
        boolean first = true;
        for (Map.Entry<String, Integer> level : BusinessPolicies.MIN_STOCK_LEVELS.entrySet()) {
            if (!first) {
                section.append(", ");
            }
            section.append(level.getKey()).append(" ≥ ").append(level.getValue());
            first = false;
        }
        return section.append(".\n\n").toString();
    }

    private String marketAnalysisSection(String requestText) {
        try {
            return marketAnalysis.requestAnalysis(requestText) + "\n";
        } catch (A2aClientException e) {
            return "_Market analysis unavailable: " + e.getMessage() + "_\n";
        }
    }
}
