package io.github.marcofanti.aauth.demo.backend.optimization;

import io.github.marcofanti.aauth.demo.backend.activity.ActivityService;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Drives optimization requests. {@link #start} returns immediately and runs the A2A call on a
 * background executor — the two-channel design the AAuth consent flow depends on: the background
 * task will later block polling the Person Server while the UI polls {@link #get} for progress.
 */
@Service
public class OptimizationService {

    static final String DEFAULT_PROMPT = "optimize laptop supply chain";

    private static final Logger log = LoggerFactory.getLogger(OptimizationService.class);

    private final Map<String, OptimizationRecord> records = new ConcurrentHashMap<>();
    private final SupplyChainGateway gateway;
    private final ActivityService activities;
    private final ExecutorService executor;

    public OptimizationService(SupplyChainGateway gateway, ActivityService activities, ExecutorService executor) {
        this.gateway = gateway;
        this.activities = activities;
        this.executor = executor;
    }

    /** Accepts the request and schedules the A2A call; never blocks on the agent chain. */
    public String start(String customPrompt) {
        String requestId = UUID.randomUUID().toString();
        String prompt = customPrompt == null || customPrompt.isBlank() ? DEFAULT_PROMPT : customPrompt;
        records.put(requestId, OptimizationRecord.pending(requestId, prompt, Instant.now()));
        activities.record("backend", "Optimization accepted: \"" + prompt + "\"");
        executor.submit(() -> run(requestId, prompt));
        return requestId;
    }

    public Optional<OptimizationRecord> get(String requestId) {
        return Optional.ofNullable(records.get(requestId));
    }

    /** Newest first. */
    public List<OptimizationRecord> all() {
        return records.values().stream()
                .sorted(Comparator.comparing(OptimizationRecord::createdAt).reversed())
                .toList();
    }

    public void clear() {
        records.clear();
    }

    private void run(String requestId, String prompt) {
        update(requestId, record -> record.withStatus(OptimizationStatus.RUNNING));
        activities.record("backend", "Delegating to supply-chain-agent");
        try {
            String report = gateway.optimize(prompt);
            update(requestId, record -> record.completed(report, Instant.now()));
            activities.record("supply-chain-agent", "Optimization completed");
        } catch (Exception e) {
            log.error("Optimization {} failed", requestId, e);
            update(requestId, record -> record.failed(e.getMessage()));
            activities.record("backend", "Optimization failed: " + e.getMessage());
        }
    }

    private void update(String requestId, UnaryOperator<OptimizationRecord> transition) {
        records.computeIfPresent(requestId, (id, record) -> transition.apply(record));
    }
}
