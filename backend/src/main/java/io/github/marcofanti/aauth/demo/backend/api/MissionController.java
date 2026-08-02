package io.github.marcofanti.aauth.demo.backend.api;

import io.github.marcofanti.aauth.demo.backend.api.MissionDtos.ProgressResponse;
import io.github.marcofanti.aauth.demo.backend.api.MissionDtos.StartRequest;
import io.github.marcofanti.aauth.demo.backend.api.MissionDtos.StartResponse;
import io.github.marcofanti.aauth.demo.backend.mission.MissionService;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mission API. Missions need the agent's Person Server identity, so the service bean exists
 * only in {@code jwt} mode; other modes get 503 with a pointer to the right run mode.
 */
@RestController
@RequestMapping("/missions")
public class MissionController {

    private final ObjectProvider<MissionService> missions;

    public MissionController(ObjectProvider<MissionService> missions) {
        this.missions = missions;
    }

    @PostMapping("/start")
    public ResponseEntity<Object> start(@RequestBody(required = false) StartRequest request) {
        MissionService service = missions.getIfAvailable();
        if (service == null) {
            return unavailable();
        }
        String description = request == null ? null : request.description();
        List<String> products = request == null ? null : request.products();
        String missionId = service.start(description, products);
        return ResponseEntity.ok(new StartResponse(missionId, "started"));
    }

    @GetMapping("/progress/{missionId}")
    public ResponseEntity<Object> progress(@PathVariable String missionId) {
        MissionService service = missions.getIfAvailable();
        if (service == null) {
            return unavailable();
        }
        return service.get(missionId)
                .<ResponseEntity<Object>>map(record -> ResponseEntity.ok(ProgressResponse.from(record)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/all")
    public ResponseEntity<Object> all() {
        MissionService service = missions.getIfAvailable();
        if (service == null) {
            return unavailable();
        }
        return ResponseEntity.ok(
                service.all().stream().map(ProgressResponse::from).toList());
    }

    private static ResponseEntity<Object> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "Missions require agent identity — start with scripts/run-demo.sh missions"));
    }
}
