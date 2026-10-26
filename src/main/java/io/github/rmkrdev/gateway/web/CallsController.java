package io.github.rmkrdev.gateway.web;

import io.github.rmkrdev.gateway.tracking.CallRecord;
import io.github.rmkrdev.gateway.tracking.CallStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Looks up the stored cost and latency of one call by the id the chat endpoint returned. */
@RestController
@RequestMapping("/v1/calls")
@ConditionalOnProperty(name = "gateway.tracking.enabled", havingValue = "true", matchIfMissing = true)
class CallsController {

    private final CallStore store;

    CallsController(CallStore store) {
        this.store = store;
    }

    @GetMapping("/{id}")
    ResponseEntity<CallRecord> find(@PathVariable String id) {
        return ResponseEntity.of(store.find(id));
    }
}
