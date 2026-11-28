package com.hs.api.controller.admin;

import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.common.context.CurrentUser;
import com.hs.common.dto.*;
import com.hs.contract.blockchain.api.*;
import com.hs.payment.membership.AdminEthPaymentQueries;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

@RestController
@RequiredArgsConstructor
@RequestMapping({"/admin/blockchain", "/api/v1/admin/blockchain"})
@PreAuthorize("hasAuthority('ADMIN') and hasAuthority('BLOCKCHAIN_VIEW')")
public class AdminBlockchainController {
    private final AdminBlockchainService service;

    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<AdminBlockchainResponse.Summary>> summary(@RequestParam MultiValueMap<String, String> query) {
        authorized(); validateKeys(query, Set.of());
        return result(service.summary());
    }
    @GetMapping("/proofs")
    public ResponseEntity<PageResponse<AdminBlockchainResponse.ProofRow>> proofs(@RequestParam MultiValueMap<String, String> query) {
        authorized();
        var request = ProofQuery.parse(query);
        return ResponseEntity.ok().header("Cache-Control", "private, no-store").body(new PageResponse<>(service.proofs(
                request.status(), request.contractNumber(), request.from(), request.to(), PageRequest.of(request.page()-1, request.size()))));
    }
    @GetMapping("/proofs/{documentId}")
    public ResponseEntity<ApiResponse<AdminBlockchainResponse.ProofDetail>> proof(@PathVariable String documentId,
            @RequestParam MultiValueMap<String, String> query) {
        authorized(); validateKeys(query, Set.of());
        return result(service.proof(documentId));
    }
    @PostMapping("/proofs/{documentId}/verify")
    public ResponseEntity<ApiResponse<AdminBlockchainResponse.Verification>> verify(@PathVariable String documentId,
            @RequestParam MultiValueMap<String, String> query, @RequestBody(required=false) String body) {
        authorized(); noInput(query, body);
        return result(service.verify(documentId));
    }
    @PreAuthorize("hasAuthority('ADMIN') and hasAuthority('BLOCKCHAIN_VIEW') and hasAuthority('CONTRACT_VIEW')")
    @PostMapping("/proofs/{documentId}/integrity")
    public ResponseEntity<ApiResponse<DocumentIntegrityResponse>> integrity(@PathVariable String documentId,
            @RequestParam MultiValueMap<String, String> query, @RequestBody(required=false) String body) {
        authorized(); CurrentUser.requireAdminWith("CONTRACT_VIEW"); noInput(query, body);
        return result(service.integrity(documentId));
    }
    @GetMapping("/membership-eth-payments")
    public ResponseEntity<PageResponse<AdminEthPaymentQueries.Payment>> payments(@RequestParam MultiValueMap<String, String> query) {
        authorized(); validateKeys(query, Set.of("unsettled", "page", "size"));
        String unsettled = query.getFirst("unsettled");
        if (unsettled != null && !Set.of("true", "false").contains(unsettled)) throw invalid();
        return ResponseEntity.ok().header("Cache-Control", "private, no-store").body(new PageResponse<>(service.payments(
                "true".equals(unsettled), PageRequest.of(number(query,"page",1)-1, Math.min(50,number(query,"size",20))))));
    }
    public record ProofQuery(String status, String contractNumber, Instant from, Instant to, int page, int size) {
        static ProofQuery parse(MultiValueMap<String, String> query) {
            validateKeys(query, Set.of("status", "contractNumber", "from", "to", "page", "size"));
            String status = query.getFirst("status"), number = query.getFirst("contractNumber");
            if (status != null && !Set.of("PENDING", "SUBMITTED", "CONFIRMED", "FAILED").contains(status)) throw invalid();
            if (number != null && (number.isBlank() || number.length()>64 || number.chars().anyMatch(Character::isISOControl))) throw invalid();
            Instant from = instant(query.getFirst("from")), to = instant(query.getFirst("to"));
            if (from != null && to != null && !from.isBefore(to)) throw invalid();
            return new ProofQuery(status, number, from, to, number(query,"page",1), Math.min(50,number(query,"size",20)));
        }
    }
    private static void authorized() { CurrentUser.requireAdminWith("BLOCKCHAIN_VIEW"); }
    private static void noInput(MultiValueMap<String,String> query, String body) {
        validateKeys(query, Set.of()); if (body != null && !body.isBlank()) throw invalid();
    }
    private static void validateKeys(MultiValueMap<String,String> query, Set<String> allowed) {
        if (!allowed.containsAll(query.keySet()) || query.values().stream().anyMatch(v -> v.size()!=1 || v.getFirst()==null)) throw invalid();
    }
    private static int number(MultiValueMap<String,String> query, String key, int fallback) {
        String value = query.getFirst(key);
        if (value == null) return fallback;
        if (!value.matches("[1-9][0-9]{0,6}")) throw invalid();
        return Integer.parseInt(value);
    }
    private static Instant instant(String value) {
        try { return value == null ? null : Instant.parse(value); }
        catch (java.time.DateTimeException e) { throw invalid(); }
    }
    private static AppException invalid() { return new AppException(ErrorCode.INVALID_REQUEST); }
    private static <T> ResponseEntity<ApiResponse<T>> result(T value) {
        return ResponseEntity.ok().header("Cache-Control", "private, no-store").body(ApiResponse.<T>builder().result(value).build());
    }
}
