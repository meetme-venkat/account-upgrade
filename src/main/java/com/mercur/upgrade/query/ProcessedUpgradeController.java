package com.mercur.upgrade.query;

import com.mercur.upgrade.persistence.ProcessedUpgrade;
import com.mercur.upgrade.persistence.ProcessedUpgradeRepository;
import com.mercur.upgrade.persistence.ProcessingStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Read side: lists processed upgrade requests, optionally filtered by status and/or user. */
@RestController
@RequestMapping("/api/processed-upgrades")
public class ProcessedUpgradeController {

    private final ProcessedUpgradeRepository repository;

    public ProcessedUpgradeController(ProcessedUpgradeRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<ProcessedUpgrade> list(@RequestParam(required = false) ProcessingStatus status,
                                       @RequestParam(required = false) String userId) {
        return repository.findAll().stream()
                .filter(p -> status == null || p.status() == status)
                .filter(p -> userId == null || p.userId().equals(userId))
                .toList();
    }
}
