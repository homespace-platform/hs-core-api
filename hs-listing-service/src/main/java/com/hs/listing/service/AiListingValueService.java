package com.hs.listing.service;

import com.hs.listing.dto.request.AiListingSearchRequest;
import com.hs.listing.dto.request.AiListingSearchRequest.Condition;
import com.hs.listing.dto.request.AiListingValueRequest;
import com.hs.listing.dto.response.AiListingValueResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only candidate retrieval outside the customer's original rent budget. Only that rent upper
 * bound is widened; every other hard predicate still goes through the strict search compiler before
 * LIMIT. The AI service evaluates the bounded pool against the whole displayed group and user needs.
 * This tool does not declare any property better and never calls a maps/model provider.
 */
@Service
public class AiListingValueService {
    private static final Set<String> RENT_FIELDS = Set.of("rent.monthly_amount", "price_amount");
    private final NamedParameterJdbcTemplate jdbc;
    private final AiListingSearchService search;
    private final BigDecimal maxExtra;
    private final int candidateLimit;

    public AiListingValueService(NamedParameterJdbcTemplate jdbc, AiListingSearchService search,
            @Value("${listing.ai.value.max-extra-monthly}") BigDecimal maxExtra,
            @Value("${listing.ai.value.candidate-limit}") int candidateLimit) {
        if (maxExtra.signum() <= 0 || maxExtra.compareTo(new BigDecimal("500000")) > 0
                || candidateLimit < 1 || candidateLimit > 8)
            throw new IllegalArgumentException("Invalid listing boundary comparison settings");
        this.jdbc = jdbc; this.search = search; this.maxExtra = maxExtra; this.candidateLimit = candidateLimit;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AiListingValueResponse find(AiListingValueRequest input) {
        if (input == null || input.search() == null || !Integer.valueOf(2).equals(input.search().version())
                || input.displayedIds() == null || input.displayedIds().isEmpty() || input.displayedIds().size() > 4)
            throw invalid("displayedIds", "Expected 1-4 displayed listings and v2 criteria");
        List<String> ids = safeIds(input.displayedIds(), 4, "displayedIds");
        if (ids.size() != input.displayedIds().size()) throw invalid("displayedIds", "Duplicate listing IDs");
        List<String> prior = safeIds(input.excludedSuggestionIds(), 46, "excludedSuggestionIds");
        var original = input.search();
        if (original.scopeIds() != null) return empty(null);

        // Verify this page against the ORIGINAL criteria, publication and pagination exclusions.
        var eligible = search.search(copy(original, original.filters(), original.where(), ids,
                original.excludeIds(), 4, original.sort())).listingIds();
        if (eligible.size() != ids.size() || !eligible.containsAll(ids)) return empty(null);
        Long comparable = jdbc.queryForObject("""
                SELECT count(*) FROM listings l WHERE l.id IN (:ids) AND l.active=true AND l.status='PUBLISHED'
                  AND (l.expires_at IS NULL OR l.expires_at>now()) AND l.currency='VND'
                  AND l.price_unit IN ('MONTH','ROOM_MONTH') AND l.price_amount>0
                """, new MapSqlParameterSource("ids", ids), Long.class);
        if (comparable == null || comparable != ids.size()) return empty(null);
        Budget budget = budget(original);
        if (budget == null) return empty(null);
        List<AiListingSearchRequest.Filter> filters = original.filters() == null ? List.of()
                : original.filters().stream().filter(f -> !isRentUpper(f.field(), f.op())).toList();
        Condition remaining = removeUpper(original.where());
        var all = new ArrayList<Condition>();
        if (remaining != null) {
            if (remaining.all() != null && remaining.field() == null && remaining.any() == null) all.addAll(remaining.all());
            else all.add(remaining);
        }
        // Equality at the old ceiling is boundary only for an original strict '<' budget.
        all.add(Condition.leaf(budget.field(), budget.strict() ? "gte" : "gt", budget.ceiling()));
        all.add(Condition.leaf(budget.field(), "lte", budget.ceiling().add(maxExtra)));
        all.add(Condition.leaf("currency", "eq", "VND"));
        all.add(Condition.leaf("price_unit", "in", List.of("MONTH", "ROOM_MONTH")));
        Condition where = Condition.allOf(all);
        var exclusions = new LinkedHashSet<>(ids);
        exclusions.addAll(prior);
        // Main pagination IDs belong inside the original budget. Candidate exclusions instead
        // contain the displayed IDs and previously suggested boundary properties.
        List<List<String>> pools = new ArrayList<>();
        for (String sort : List.of("newest", "area_desc", "rent_asc")) {
            pools.add(search.search(copy(original, filters, where, null, List.copyOf(exclusions), candidateLimit, sort)).listingIds());
        }
        // Round-robin newest/largest/cheapest; retrieval is not a fixed quality decision.
        var candidates = new LinkedHashSet<String>();
        for (int index = 0; index < candidateLimit && candidates.size() < candidateLimit; index++) {
            for (List<String> pool : pools) {
                if (index < pool.size()) candidates.add(pool.get(index));
                if (candidates.size() == candidateLimit) break;
            }
        }
        return new AiListingValueResponse(budget.field(), budget.ceiling(), maxExtra, List.copyOf(candidates), Instant.now());
    }

    private AiListingSearchRequest copy(AiListingSearchRequest original, List<AiListingSearchRequest.Filter> filters,
                                       Condition where, List<String> scope, List<String> exclude, int limit, String sort) {
        return new AiListingSearchRequest(filters, sort, limit, original.keyword(), original.nearby(), original.version(),
                where, original.nearbyAll(), original.context(), scope, exclude, false);
    }

    private AiListingValueResponse empty(Budget budget) {
        return new AiListingValueResponse(budget == null ? null : budget.field(), budget == null ? null : budget.ceiling(),
                maxExtra, List.of(), Instant.now());
    }

    private static AiSearchValidationException invalid(String field, String message) {
        return new AiSearchValidationException("INVALID_VALUE_SELECTION", field, message);
    }

    private static List<String> safeIds(List<String> raw, int max, String field) {
        if (raw == null) return List.of();
        if (raw.size() > max) throw invalid(field, "Too many listing IDs");
        var result = new LinkedHashSet<String>();
        for (String id : raw) {
            try { result.add(UUID.fromString(id).toString()); }
            catch (RuntimeException error) { throw invalid(field, "Invalid listing ID"); }
        }
        return List.copyOf(result);
    }

    record Budget(String field, BigDecimal ceiling, boolean strict) {}

    /** An upper bound in an OR cannot safely be widened as one global customer budget. */
    static Budget budget(AiListingSearchRequest request) {
        List<Condition> bounds = new ArrayList<>();
        if (request.filters() != null) for (var filter : request.filters()) {
            if (filter != null && isRentUpper(filter.field(), filter.op())) bounds.add(Condition.leaf(filter.field(), filter.op(), filter.value()));
        }
        if (!collectBounds(request.where(), bounds)) return null;
        if (bounds.isEmpty()) return null;
        BigDecimal ceiling = null;
        boolean strict = false;
        String field = "price_amount";
        for (Condition bound : bounds) {
            BigDecimal value;
            try { value = new BigDecimal(String.valueOf(bound.value())); }
            catch (NumberFormatException error) { return null; }
            if (value.signum() <= 0) return null;
            if (bound.field().equals("rent.monthly_amount")) field = "rent.monthly_amount";
            if (ceiling == null || value.compareTo(ceiling) < 0) {
                ceiling = value; strict = "lt".equalsIgnoreCase(bound.op());
            } else if (value.compareTo(ceiling) == 0 && "lt".equalsIgnoreCase(bound.op())) strict = true;
        }
        return new Budget(field, ceiling, strict);
    }

    private static boolean collectBounds(Condition node, List<Condition> result) {
        if (node == null) return true;
        if (node.any() != null) return !containsUpper(node);
        if (node.all() != null) {
            for (Condition child : node.all()) if (!collectBounds(child, result)) return false;
        } else if (isRentUpper(node.field(), node.op())) result.add(node);
        return true;
    }

    private static boolean containsUpper(Condition node) {
        if (node == null) return false;
        if (isRentUpper(node.field(), node.op())) return true;
        return node.all() != null && node.all().stream().anyMatch(AiListingValueService::containsUpper)
                || node.any() != null && node.any().stream().anyMatch(AiListingValueService::containsUpper);
    }

    private static boolean isRentUpper(String field, String op) {
        return RENT_FIELDS.contains(Objects.toString(field, "")) && ("lt".equalsIgnoreCase(op) || "lte".equalsIgnoreCase(op));
    }

    private static Condition removeUpper(Condition node) {
        if (node == null || isRentUpper(node.field(), node.op())) return null;
        if (node.all() != null) {
            var children = node.all().stream().map(AiListingValueService::removeUpper).filter(Objects::nonNull).toList();
            return children.isEmpty() ? null : Condition.allOf(children);
        }
        return node;
    }
}