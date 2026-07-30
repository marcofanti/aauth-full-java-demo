package io.github.marcofanti.aauth.demo.sca;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Hardcoded procurement policies. The demo's value is the auth plumbing; policies stay static. */
final class BusinessPolicies {

    static final int INVENTORY_BUFFER_MONTHS = 3;
    static final int CFO_APPROVAL_THRESHOLD_USD = 50_000;
    static final int MAX_SINGLE_ORDER_USD = 100_000;
    static final int QUARTERLY_BUDGET_USD = 250_000;

    static final List<String> TIER1_VENDORS = List.of("Apple", "Dell");
    static final List<String> TIER2_VENDORS = List.of("HP", "Lenovo");

    static final Map<String, Integer> MIN_STOCK_LEVELS = minStock();

    private BusinessPolicies() {}

    private static Map<String, Integer> minStock() {
        Map<String, Integer> levels = new LinkedHashMap<>();
        levels.put("MacBook Pro 14\"", 15);
        levels.put("MacBook Air 13\"", 25);
        levels.put("Dell XPS 13", 20);
        levels.put("ThinkPad X1 Carbon", 10);
        return levels;
    }
}
