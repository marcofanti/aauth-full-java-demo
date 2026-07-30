package io.github.marcofanti.aauth.demo.backend.api;

import io.github.marcofanti.aauth.demo.backend.api.OptimizationDtos.ProgressResponse;
import io.github.marcofanti.aauth.demo.backend.api.OptimizationDtos.ResultsResponse;
import io.github.marcofanti.aauth.demo.backend.api.OptimizationDtos.StartRequest;
import io.github.marcofanti.aauth.demo.backend.api.OptimizationDtos.StartResponse;
import io.github.marcofanti.aauth.demo.backend.optimization.OptimizationService;
import io.github.marcofanti.aauth.demo.backend.optimization.OptimizationStatus;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/optimization")
public class OptimizationController {

    private final OptimizationService optimizations;

    public OptimizationController(OptimizationService optimizations) {
        this.optimizations = optimizations;
    }

    /** Returns immediately; the agent chain runs in the background and progress is polled. */
    @PostMapping("/start")
    public StartResponse start(@RequestBody(required = false) StartRequest request) {
        String prompt = request == null ? null : request.customPrompt();
        String requestId = optimizations.start(prompt);
        return new StartResponse(requestId, "started");
    }

    @GetMapping("/progress/{requestId}")
    public ResponseEntity<ProgressResponse> progress(@PathVariable String requestId) {
        return optimizations
                .get(requestId)
                .map(record -> ResponseEntity.ok(ProgressResponse.from(record)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/results/{requestId}")
    public ResponseEntity<ResultsResponse> results(@PathVariable String requestId) {
        return optimizations
                .get(requestId)
                .map(record -> record.status() == OptimizationStatus.COMPLETED
                        ? ResponseEntity.ok(ResultsResponse.from(record))
                        : ResponseEntity.status(HttpStatus.CONFLICT).<ResultsResponse>build())
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/all")
    public List<ProgressResponse> all() {
        return optimizations.all().stream().map(ProgressResponse::from).toList();
    }

    @DeleteMapping("/clear")
    public void clear() {
        optimizations.clear();
    }
}
