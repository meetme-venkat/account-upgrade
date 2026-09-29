package com.mercur.upgrade.ingestion;

import com.mercur.upgrade.common.UpgradeRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Ingestion API. Requests are only validated and published here; eligibility is decided
 * asynchronously, hence {@code 202 Accepted}. Poll {@code GET /api/processed-upgrades} for outcomes.
 *
 * <p>Clients should send an {@code Idempotency-Key} header so that retries after a timeout are
 * processed (and notified) only once.
 */
@RestController
@RequestMapping("/api")
public class IngestionController {

    static final int MAX_BATCH_SIZE = 10_000;
    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String KEY_PATTERN = "[A-Za-z0-9._:-]{1,128}";
    private static final String KEY_MESSAGE = "Idempotency-Key must be 1-128 characters of [A-Za-z0-9._:-]";

    private final IngestionService ingestionService;

    public IngestionController(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping("/batch-upgrade")
    public ResponseEntity<BatchIngestionResponse> batchUpgrade(
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false)
            @Pattern(regexp = KEY_PATTERN, message = KEY_MESSAGE) String idempotencyKey,
            @RequestBody
            @NotEmpty(message = "batch must contain at least one request")
            @Size(max = MAX_BATCH_SIZE, message = "batch must not exceed " + MAX_BATCH_SIZE + " requests")
            List<@Valid UpgradeRequest> requests) {
        BatchIngestionResponse response = ingestionService.ingestBatch(requests, idempotencyKey);
        HttpStatus status = response.accepted() > 0 ? HttpStatus.ACCEPTED : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(response);
    }

    @PostMapping("/realtime-upgrade")
    public ResponseEntity<IngestionReceipt> realtimeUpgrade(
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false)
            @Pattern(regexp = KEY_PATTERN, message = KEY_MESSAGE) String idempotencyKey,
            @RequestBody @Valid UpgradeRequest request) {
        return ResponseEntity.accepted().body(ingestionService.ingestRealtime(request, idempotencyKey));
    }
}
