package io.github.marcofanti.aauth.demo.backend.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MetaController {

    public record ServiceInfo(String service, String version) {}

    public record Health(String status, String service) {}

    public record UserInfo(String id, String username) {}

    @GetMapping("/")
    public ServiceInfo root() {
        return new ServiceInfo("aauth-demo-backend", "0.1.0");
    }

    @GetMapping("/health")
    public Health health() {
        return new Health("healthy", "aauth-demo-backend");
    }

    /** No human login in this demo; the UI is unprotected and always acts as a guest. */
    @GetMapping("/auth/me")
    public UserInfo me() {
        return new UserInfo("guest", "guest");
    }
}
