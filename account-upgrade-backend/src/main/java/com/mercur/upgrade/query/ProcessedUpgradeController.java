package com.mercur.upgrade.query;

import com.mercur.upgrade.persistence.ProcessedUpgrade;
import com.mercur.upgrade.persistence.ProcessedUpgradeRepository;
import com.mercur.upgrade.persistence.ProcessingStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read side: lists processed upgrade requests in store order, optionally filtered by status and/or
 * user. Filters run in SQL and the result is capped at the
 * newest {@code limit} records, so the endpoint stays cheap however large the store grows.
 */
@RestController
@RequestMapping("/api/processed-upgrades")
public class ProcessedUpgradeController {

    static final int DEFAULT_LIMIT = 1000;
    static final int MAX_LIMIT = 5000;

    private final ProcessedUpgradeRepository repository;

    public ProcessedUpgradeController(ProcessedUpgradeRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<ProcessedUpgrade> list(@RequestParam(required = false) ProcessingStatus status,
                                       @RequestParam(required = false) String userId,
                                       @RequestParam(defaultValue = "" + DEFAULT_LIMIT)
                                       @Min(value = 1, message = "limit must be at least 1")
                                       @Max(value = MAX_LIMIT, message = "limit must not exceed " + MAX_LIMIT)
                                       int limit) {
        return repository.find(status, userId, limit);
    }
}
