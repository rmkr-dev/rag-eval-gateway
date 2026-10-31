package io.github.rmkrdev.gateway.web;

import io.github.rmkrdev.gateway.auth.CallerIdentity;
import io.github.rmkrdev.gateway.auth.GatewayRoles;
import io.github.rmkrdev.gateway.tracking.CallRecord;
import io.github.rmkrdev.gateway.tracking.CallStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.annotation.Secured;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Looks up the stored cost and latency of one call by the id the chat endpoint returned. A
 * caller sees only its own calls; {@link GatewayRoles#REPORTS} sees every call. Someone else's
 * call is a 404, the same as an unknown id.
 */
@RestController
@RequestMapping("/v1/calls")
@ConditionalOnProperty(name = "gateway.tracking.enabled", havingValue = "true", matchIfMissing = true)
@Secured({GatewayRoles.CALLER, GatewayRoles.REPORTS})
class CallsController {

    private final CallStore store;
    private final CallerIdentity callers;

    CallsController(CallStore store, CallerIdentity callers) {
        this.store = store;
        this.callers = callers;
    }

    @GetMapping("/{id}")
    ResponseEntity<CallRecord> find(@PathVariable String id) {
        boolean all = callers.canReadAllCalls();
        String client = all ? null : callers.client(null);
        return ResponseEntity.of(store.find(id).filter(call -> all || call.client().equals(client)));
    }
}
