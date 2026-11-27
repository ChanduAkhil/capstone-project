
package com.easycart.traffic_router;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@RestController
@RequestMapping("/api")
public class TrafficRouterController {

    private final RestTemplate restTemplate = new RestTemplate();
    private final Random random = new Random();

    private volatile int canaryPercentage = 10;
    private volatile boolean rollbackTriggered = false;

    private static final double LATENCY_THRESHOLD_MS = 300.0;
    private static final long MIN_CANARY_SAMPLES = 5;

    private final AtomicLong baselineRequests = new AtomicLong(0);
    private final AtomicLong canaryRequests = new AtomicLong(0);
    private final AtomicLong canaryLatencyTotalNanos = new AtomicLong(0);
    private final AtomicLong canaryLatencySamples = new AtomicLong(0);

    private final String baselineUrl = "http://localhost:8081";
    private final String canaryUrl = "http://localhost:8082";

    @PostMapping("/users/login")
    public ResponseEntity<String> routeLogin(
            @RequestBody String requestBody) {

        boolean useCanary;

        synchronized (this) {
            useCanary = !rollbackTriggered
                    && random.nextInt(100) < canaryPercentage;

            if (useCanary) {
                canaryRequests.incrementAndGet();
            } else {
                baselineRequests.incrementAndGet();
            }
        }

        String targetUrl = useCanary ? canaryUrl : baselineUrl;
        long startNanos = System.nanoTime();

        System.out.println("Routing login request to: "
                + (useCanary ? "CANARY" : "BASELINE")
                + " | Canary percentage: " + canaryPercentage + "%");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<String> request =
                new HttpEntity<>(requestBody, headers);

        try {
            ResponseEntity<String> response =
                    restTemplate.postForEntity(
                            targetUrl + "/api/users/login",
                            request,
                            String.class);

            return ResponseEntity.status(response.getStatusCode())
                    .body(response.getBody());

        } catch (HttpStatusCodeException ex) {
            return ResponseEntity.status(ex.getStatusCode())
                    .body(ex.getResponseBodyAsString());

        } catch (RestClientException ex) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body("Backend unavailable: " + ex.getMessage());

        } finally {
            if (useCanary) {
                long elapsedNanos = System.nanoTime() - startNanos;

                canaryLatencyTotalNanos.addAndGet(elapsedNanos);
                long samples = canaryLatencySamples.incrementAndGet();

                double averageMs =
                        canaryLatencyTotalNanos.get()
                                / 1_000_000.0 / samples;

                System.out.printf(
                        "Canary average latency: %.2f ms%n",
                        averageMs);

                if (samples >= MIN_CANARY_SAMPLES
                        && averageMs > LATENCY_THRESHOLD_MS) {
                    triggerRollback(averageMs);
                }
            }
        }
    }

    private synchronized void triggerRollback(double averageMs) {
        if (!rollbackTriggered) {
            rollbackTriggered = true;
            canaryPercentage = 0;

            System.out.printf(
                    "REGRESSION DETECTED! Average Canary latency: "
                            + "%.2f ms. Rolling back Canary traffic to 0%%.%n",
                    averageMs);
        }
    }

    @GetMapping("/router/config")
    public Map<String, Object> getConfig() {
        return Map.of(
                "canaryPercentage", canaryPercentage,
                "rollbackTriggered", rollbackTriggered,
                "latencyThresholdMs", LATENCY_THRESHOLD_MS
        );
    }

    @PostMapping("/router/config")
    public synchronized ResponseEntity<Map<String, Object>>
            setCanaryPercentage(@RequestParam int percentage) {

        if (percentage < 0 || percentage > 100) {
            return ResponseEntity.badRequest().body(
                    Map.of("error",
                            "Percentage must be between 0 and 100"));
        }

        if (rollbackTriggered && percentage > 0) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(
                    Map.of(
                            "message",
                            "Canary rollback is active. Restart the router "
                                    + "to reset the experiment."
                    ));
        }

        canaryPercentage = percentage;

        return ResponseEntity.ok(Map.of(
                "message", "Canary traffic updated",
                "canaryPercentage", canaryPercentage
        ));
    }

    @GetMapping("/router/stats")
    public Map<String, Object> getStats() {
        long baseline = baselineRequests.get();
        long canary = canaryRequests.get();
        long total = baseline + canary;
        long samples = canaryLatencySamples.get();

        double actualCanaryPercentage = total == 0
                ? 0.0
                : canary * 100.0 / total;

        double averageCanaryLatencyMs = samples == 0
                ? 0.0
                : canaryLatencyTotalNanos.get()
                        / 1_000_000.0 / samples;

        return Map.of(
                "baselineRequests", baseline,
                "canaryRequests", canary,
                "totalRequests", total,
                "configuredCanaryPercentage", canaryPercentage,
                "actualCanaryPercentage",
                Math.round(actualCanaryPercentage * 100.0) / 100.0,
                "averageCanaryLatencyMs",
                Math.round(averageCanaryLatencyMs * 100.0) / 100.0,
                "canaryLatencySamples", samples,
                "latencyThresholdMs", LATENCY_THRESHOLD_MS,
                "rollbackTriggered", rollbackTriggered
        );
    }
}
