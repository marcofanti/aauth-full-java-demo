package io.github.marcofanti.aauth.demo.backend.api;

import io.github.marcofanti.aauth.demo.backend.activity.ActivityEntry;
import io.github.marcofanti.aauth.demo.backend.activity.ActivityService;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/agents")
public class AgentsController {

    public record AgentInfo(String id, String url, String status) {}

    private final ActivityService activities;
    private final List<AgentInfo> agents;

    public AgentsController(
            ActivityService activities,
            @Value("${demo.supply-chain-url:http://gateway.uma.lab:9999/}") String supplyChainUrl,
            @Value("${demo.market-analysis-url:http://gateway.uma.lab:9998/}") String marketAnalysisUrl) {
        this.activities = activities;
        this.agents = List.of(
                new AgentInfo("supply-chain-agent", supplyChainUrl, "configured"),
                new AgentInfo("market-analysis-agent", marketAnalysisUrl, "configured"));
    }

    @GetMapping("/status")
    public List<AgentInfo> status() {
        return agents;
    }

    @GetMapping("/activities")
    public List<ActivityEntry> activities(@RequestParam(defaultValue = "50") int limit) {
        return activities.list(limit);
    }

    @DeleteMapping("/activities")
    public void clearActivities() {
        activities.clear();
    }
}
