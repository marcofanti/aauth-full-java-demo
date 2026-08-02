package io.github.marcofanti.aauth.demo.backend.mission;

import io.github.marcofanti.aauth.demo.backend.activity.ActivityService;
import io.github.marcofanti.aauth.demo.backend.optimization.SupplyChainGateway;
import io.github.marcofanti.aauth.demo.common.MissionClient;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs multi-step missions: propose the mission to the Person Server (one user approval),
 * then per-step {@code /permission} checks — steps whose action is in the mission's approved
 * tools auto-grant, so the optimization loop runs without further prompts; the closing
 * {@code inventory:purchase} step is deliberately outside them and defers to the user again.
 * Every completed step lands in the Person Server's mission log via {@code /audit}.
 */
public class MissionService {

    static final String DEFAULT_DESCRIPTION = "Optimize the supply chain for the listed products and report findings. "
            + "Any inventory purchase requires my explicit approval.";
    static final List<String> DEFAULT_PRODUCTS = List.of("laptop", "monitor");
    static final String OPTIMIZE_ACTION = "supply-chain:optimize";
    static final String ANALYZE_ACTION = "market-analysis:analyze";
    static final String PURCHASE_ACTION = "inventory:purchase";

    private static final Logger log = LoggerFactory.getLogger(MissionService.class);
    private static final Map<String, String> APPROVED_TOOLS = Map.of(
            OPTIMIZE_ACTION, "Run a supply-chain optimization via the supply-chain agent",
            ANALYZE_ACTION, "Request market analysis via the market-analysis agent");

    private final Map<String, MissionRecord> records = new ConcurrentHashMap<>();
    private final MissionClient missions;
    private final SupplyChainGateway gateway;
    private final ActivityService activities;
    private final ExecutorService executor;

    public MissionService(
            MissionClient missions, SupplyChainGateway gateway, ActivityService activities, ExecutorService executor) {
        this.missions = missions;
        this.gateway = gateway;
        this.activities = activities;
        this.executor = executor;
    }

    /** Accepts the mission and schedules the run; never blocks on approval or the agent chain. */
    public String start(String customDescription, List<String> customProducts) {
        String missionId = UUID.randomUUID().toString();
        String description =
                customDescription == null || customDescription.isBlank() ? DEFAULT_DESCRIPTION : customDescription;
        List<String> products =
                customProducts == null || customProducts.isEmpty() ? DEFAULT_PRODUCTS : List.copyOf(customProducts);
        records.put(missionId, MissionRecord.pending(missionId, description, Instant.now()));
        activities.record("backend", "Mission accepted: \"" + description + "\"");
        executor.submit(() -> run(missionId, description, products));
        return missionId;
    }

    public Optional<MissionRecord> get(String missionId) {
        return Optional.ofNullable(records.get(missionId));
    }

    /** Newest first. */
    public List<MissionRecord> all() {
        return records.values().stream()
                .sorted(Comparator.comparing(MissionRecord::createdAt).reversed())
                .toList();
    }

    private void run(String missionId, String description, List<String> products) {
        try {
            MissionClient.Mission mission = missions.propose(description, APPROVED_TOOLS, (url, code) -> {
                update(missionId, record -> record.awaitingApproval(url, code));
                activities.record("backend", "Mission awaiting user approval (code " + code + ")");
            });
            update(missionId, record -> record.running(mission.s256()));
            activities.record("backend", "Mission approved (s256 " + mission.s256() + ")");

            for (String product : products) {
                optimizeStep(missionId, mission, product);
            }
            purchaseStep(missionId, mission, products.getFirst());

            update(missionId, record -> record.completed(Instant.now()));
            activities.record("backend", "Mission completed");
        } catch (Exception e) {
            log.error("Mission {} failed", missionId, e);
            update(missionId, record -> record.failed(e.getMessage()));
            activities.record("backend", "Mission failed: " + e.getMessage());
        }
    }

    /** In approved tools: the permission auto-grants and the A2A chain runs without a prompt. */
    private void optimizeStep(String missionId, MissionClient.Mission mission, String product) throws Exception {
        MissionClient.Permission permission = missions.permission(
                mission,
                OPTIMIZE_ACTION,
                "Optimize the " + product + " supply chain",
                Map.of("product", product),
                interactionCallback(missionId));
        if (permission == MissionClient.Permission.DENIED) {
            addStep(missionId, new MissionStep(OPTIMIZE_ACTION, product, "denied"));
            activities.record("backend", "Permission denied for " + product + " optimization");
            return;
        }
        addStep(missionId, new MissionStep(OPTIMIZE_ACTION, product, "granted"));
        String report = gateway.optimize("optimize " + product + " supply chain", interactionCallback(missionId));
        missions.audit(
                mission,
                OPTIMIZE_ACTION,
                "Optimized the " + product + " supply chain",
                Map.of("summary", excerpt(report)));
        addStep(missionId, new MissionStep(OPTIMIZE_ACTION, product, "completed"));
        activities.record("supply-chain-agent", "Mission step completed: " + product);
    }

    /** Outside approved tools: the Person Server defers to the user; their decision is the result. */
    private void purchaseStep(String missionId, MissionClient.Mission mission, String product) {
        MissionClient.Permission permission = missions.permission(
                mission,
                PURCHASE_ACTION,
                "Purchase 500 units of " + product + " to cover the projected shortfall",
                Map.of("product", product, "units", 500),
                interactionCallback(missionId));
        String outcome = permission == MissionClient.Permission.GRANTED ? "granted" : "denied";
        addStep(missionId, new MissionStep(PURCHASE_ACTION, product, outcome));
        activities.record("backend", "Purchase " + outcome + " for " + product);
        if (permission == MissionClient.Permission.GRANTED) {
            missions.audit(
                    mission,
                    PURCHASE_ACTION,
                    "Purchased 500 units of " + product,
                    Map.of("product", product, "units", 500));
        }
    }

    private BiConsumer<String, String> interactionCallback(String missionId) {
        return (url, code) -> {
            update(missionId, record -> record.interactionRequired(url, code));
            activities.record("backend", "Mission step needs user decision (code " + code + ")");
        };
    }

    private void addStep(String missionId, MissionStep step) {
        update(missionId, record -> record.withStep(step));
    }

    private void update(String missionId, UnaryOperator<MissionRecord> transition) {
        records.computeIfPresent(missionId, (id, record) -> transition.apply(record));
    }

    private static String excerpt(String report) {
        if (report == null) {
            return "";
        }
        return report.length() <= 200 ? report : report.substring(0, 200) + "…";
    }
}
