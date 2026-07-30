package io.github.marcofanti.aauth.demo.maa;

import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Keyword-routes a request into one of four canned analyses and renders a Markdown report.
 * Deterministic arithmetic over {@link MarketData}; no external calls.
 */
@Component
public class MarketAnalyzer {

    public String analyze(String requestText) {
        String request = requestText == null ? "" : requestText.toLowerCase(Locale.ROOT);
        int months = horizonMonths(request);
        if (request.contains("comprehensive")) {
            return "# Market Analysis Report (comprehensive, " + months + "-month horizon)\n\n" + demandSection(months)
                    + "\n" + trendSection(months) + "\n" + patternSection();
        }
        if (request.contains("trend") || request.contains("forecast")) {
            return "# Market Analysis Report\n\n" + trendSection(months);
        }
        if (request.contains("pattern")) {
            return "# Market Analysis Report\n\n" + patternSection();
        }
        return "# Market Analysis Report\n\n" + demandSection(months);
    }

    static int horizonMonths(String request) {
        if (request.contains("year")) {
            return 12;
        }
        if (request.contains("quarter")) {
            return 3;
        }
        return 6;
    }

    private static String demandSection(int months) {
        int hiringDemand = MarketData.PLANNED_HIRES_NEXT_6_MONTHS.values().stream()
                        .mapToInt(Integer::intValue)
                        .sum()
                * months
                / 6;
        int refreshDemand = MarketData.FLEET_SIZE * months / MarketData.REFRESH_CYCLE_MONTHS;
        int totalDemand = hiringDemand + refreshDemand;
        int totalStock = MarketData.CURRENT_INVENTORY.values().stream()
                .mapToInt(Integer::intValue)
                .sum();
        StringBuilder section = new StringBuilder();
        section.append("## Laptop Demand (next ").append(months).append(" months)\n\n");
        section.append("| Driver | Units |\n|---|---|\n");
        section.append("| New hires | ").append(hiringDemand).append(" |\n");
        section.append("| Fleet refresh | ").append(refreshDemand).append(" |\n");
        section.append("| **Total demand** | **").append(totalDemand).append("** |\n\n");
        section.append("Current stock across models: ").append(totalStock).append(" units.\n");
        if (totalDemand > totalStock) {
            section.append("**Projected shortfall: ")
                    .append(totalDemand - totalStock)
                    .append(" units** — procurement should start this quarter.\n");
        } else {
            section.append("Current stock covers projected demand for this horizon.\n");
        }
        return section.toString();
    }

    private static String trendSection(int months) {
        return "## Market Trends (next " + months + " months)\n\n"
                + "- Engineering headcount growing at " + percent(MarketData.ENGINEERING_GROWTH_RATE)
                + " annually — the dominant demand driver.\n"
                + "- Sales " + percent(MarketData.SALES_GROWTH_RATE) + ", Marketing "
                + percent(MarketData.MARKETING_GROWTH_RATE) + ", Operations "
                + percent(MarketData.OPERATIONS_GROWTH_RATE) + " annual growth.\n"
                + "- Premium laptop prices trending " + percent(MarketData.PREMIUM_LAPTOP_PRICE_TREND)
                + " year over year; modest savings from deferring non-urgent orders.\n"
                + "- Vendor release cycles suggest new flagship models mid-cycle; avoid large orders "
                + "immediately before announcements.\n";
    }

    private static String patternSection() {
        StringBuilder section = new StringBuilder();
        section.append("## Demand Patterns\n\n");
        section.append("- Onboarding peaks in Q3 (new-grad start dates) and Q1 (post-budget hiring).\n");
        section.append("- Fleet-refresh requests cluster at the ")
                .append(MarketData.REFRESH_CYCLE_MONTHS)
                .append("-month device age mark.\n");
        section.append("- Current inventory by model:\n\n");
        for (Map.Entry<String, Integer> entry : MarketData.CURRENT_INVENTORY.entrySet()) {
            section.append("  - ")
                    .append(entry.getKey())
                    .append(": ")
                    .append(entry.getValue())
                    .append(" units\n");
        }
        return section.toString();
    }

    private static String percent(double rate) {
        return Math.round(rate * 100) + "%";
    }
}
