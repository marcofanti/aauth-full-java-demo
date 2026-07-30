package io.github.marcofanti.aauth.demo.maa;

import java.util.LinkedHashMap;
import java.util.Map;

/** Canned market data. The demo's value is the auth plumbing; this data is deliberately static. */
final class MarketData {

    static final int FLEET_SIZE = 820;
    static final int REFRESH_CYCLE_MONTHS = 36;

    static final Map<String, Integer> CURRENT_INVENTORY = inventory();
    static final Map<String, Integer> PLANNED_HIRES_NEXT_6_MONTHS = hires();

    static final double ENGINEERING_GROWTH_RATE = 0.22;
    static final double SALES_GROWTH_RATE = 0.14;
    static final double MARKETING_GROWTH_RATE = 0.09;
    static final double OPERATIONS_GROWTH_RATE = 0.07;
    static final double PREMIUM_LAPTOP_PRICE_TREND = -0.03;

    private MarketData() {}

    private static Map<String, Integer> inventory() {
        Map<String, Integer> stock = new LinkedHashMap<>();
        stock.put("MacBook Pro 14\"", 40);
        stock.put("MacBook Air 13\"", 72);
        stock.put("Dell XPS 13", 55);
        stock.put("ThinkPad X1 Carbon", 28);
        return stock;
    }

    private static Map<String, Integer> hires() {
        Map<String, Integer> plan = new LinkedHashMap<>();
        plan.put("Engineering", 24);
        plan.put("Sales", 12);
        plan.put("Marketing", 9);
        plan.put("Operations", 6);
        return plan;
    }
}
