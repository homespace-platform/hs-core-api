package com.hs.contract.service.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.common.time.BillingTime;
import com.hs.contract.advice.ContractErrorCode;
import com.hs.contract.dto.ai.ContractLeaseRow;
import com.hs.contract.model.*;
import com.hs.contract.model.constant.ContractStatus;
import com.hs.contract.repository.*;
import com.hs.contract.service.ContractAmendmentService;
import com.hs.contract.service.ContractLeaseCalc;
import com.hs.contract.service.ContractTermsResolver;
import com.hs.listing.model.RentalRequest;
import com.hs.listing.repository.RentalRequestRepository;
import com.hs.payment.model.DepositRecord;
import com.hs.payment.model.PaymentRequest;
import com.hs.payment.model.constant.PaymentStatus;
import com.hs.payment.repository.DepositRecordRepository;
import com.hs.payment.repository.PaymentRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Read-only projections of the caller's own contracts for the AI assistant.
 *
 * <p>Invariants: the caller identity is always supplied by the authenticated request (never by the model);
 * every entry point re-checks that the caller is a party of a visible root contract; nothing here writes,
 * locks, publishes or synchronises state (the lifecycle {@code get} endpoints do, so they are not reused);
 * dates come from {@link BillingTime}; money leaves as exact strings; missing facts are {@code null}, never 0.
 */
@Service
@RequiredArgsConstructor
public class AiContractQueryService {
    static final int ROW_CAP = 5000;
    static final int MAX_PAGE_SIZE = 20;
    private static final Set<String> SIGNING = Set.of("DRAFT", "PENDING_REVIEW", "LANDLORD_SIGNATURE_PENDING", "TENANT_SIGNATURE_PENDING");
    private static final Set<String> TERM_GROUPS = Set.of("LEASE", "PRICING", "CHARGES", "EQUIPMENT", "AMENITIES",
            "POLICIES", "SPECIAL_TERMS", "PROPERTY", "OCCUPANCY");
    private static final List<String> PROPERTY_KEYS = List.of("listingCode", "fullAddress", "propertyType", "category",
            "unitNumber", "floor", "buildingName", "projectName", "buildingBlock", "areaM2", "maxOccupants", "maxVehicles");

    private final ContractRepository contracts;
    private final ContractRevisionRepository revisions;
    private final ContractAmendmentRepository amendments;
    private final ContractRenewalRepository renewals;
    private final ContractClosureRepository closures;
    private final MonthlyInvoiceRepository invoices;
    private final RentalRequestRepository rentals;
    private final DepositRecordRepository deposits;
    private final PaymentRequestRepository payments;
    private final ObjectMapper mapper;
    private final BillingTime time;

    // ------------------------------------------------------------------ summary / search

    @Transactional(readOnly = true)
    public Map<String, Object> summarize(String userId, String role, Integer expiringWithinDays) {
        requireUser(userId);
        String roleFilter = role(role);
        int window = days(expiringWithinDays, 30);
        LocalDate today = time.today();
        List<ContractLeaseRow> all = rows(userId);
        boolean complete = all.size() <= ROW_CAP;
        List<View> views = all.stream().limit(ROW_CAP).map(r -> view(r, userId, today))
                .filter(v -> v.landlord || v.tenant) // defence in depth: the query already excludes tenant drafts
                .filter(v -> roleFilter == null || v.hasRole(roleFilter)).toList();

        Map<String, Long> byStatus = new TreeMap<>(), byRole = new TreeMap<>(), byPhase = new TreeMap<>();
        long inForce = 0, notStarted = 0, ended = 0, byMe = 0, byOther = 0;
        List<View> expiring = new ArrayList<>();
        for (View v : views) {
            byStatus.merge(v.row.status().name(), 1L, Long::sum);
            byRole.merge(v.primaryRole, 1L, Long::sum);
            byPhase.merge(v.phase, 1L, Long::sum);
            if (v.inForce()) inForce++;
            if (v.row.status() == ContractStatus.ACTIVE && "NOT_STARTED".equals(v.phase)) notStarted++;
            if ("LEASE_ENDED".equals(v.phase)) ended++;
            String pending = pendingSigner(v.row.status());
            if (pending != null) { if (v.primaryRole.equals(pending)) byMe++; else byOther++; }
            if (v.inForce() && v.daysRemaining != null && v.daysRemaining <= window) expiring.add(v);
        }
        expiring.sort(Comparator.comparing(v -> v.lease.endExclusive));
        Map<String, Object> out = obj("meta", meta(), "scope", roleFilter == null ? "ANY" : roleFilter,
                "complete", complete, "total", (long) views.size(), "byStatus", byStatus, "byRole", byRole,
                "byLeasePhase", byPhase, "inForce", inForce, "signedNotStarted", notStarted,
                "leaseEndedNotClosed", ended);
        out.put("awaitingSignature", obj("fromAsker", byMe, "fromCounterparty", byOther));
        out.put("expiring", obj("withinDays", window, "count", (long) expiring.size(),
                "contracts", expiring.stream().limit(10).map(this::ref).toList()));
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> search(String userId, String role, List<String> statuses, String leasePhase,
                                      Integer expiringWithinDays, String text, String sort, Integer page, Integer size) {
        requireUser(userId);
        String roleFilter = role(role);
        Set<ContractStatus> statusFilter = new HashSet<>();
        if (statuses != null) {
            if (statuses.size() > 9) throw invalid();
            for (String s : statuses) statusFilter.add(parse(ContractStatus.class, s));
        }
        String phaseFilter = leasePhase == null || leasePhase.isBlank() ? null : leasePhase.trim().toUpperCase(Locale.ROOT);
        if (phaseFilter != null && !Set.of("NOT_SIGNED", "NOT_STARTED", "IN_LEASE", "LEASE_ENDED", "CLOSED",
                "TERMINATED", "CANCELLED", "UNKNOWN").contains(phaseFilter)) throw invalid();
        Integer expiring = expiringWithinDays == null ? null : days(expiringWithinDays, 30);
        String order = sort == null || sort.isBlank() ? "CREATED_DESC" : sort.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("CREATED_DESC", "LEASE_END_ASC", "LEASE_END_DESC").contains(order)) throw invalid();
        int pageNo = page == null ? 1 : page, pageSize = size == null ? 5 : size;
        if (pageNo < 1 || pageNo > 1000 || pageSize < 1 || pageSize > MAX_PAGE_SIZE) throw invalid();
        String pattern = null;
        if (text != null && !text.isBlank()) {
            String t = text.trim().toLowerCase(Locale.ROOT);
            if (t.length() > 80) throw invalid();
            pattern = "%" + t.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        }

        LocalDate today = time.today();
        List<ContractLeaseRow> all = rows(userId);
        List<View> views = all.stream().limit(ROW_CAP).map(r -> view(r, userId, today))
                .filter(v -> v.landlord || v.tenant)
                .filter(v -> roleFilter == null || v.hasRole(roleFilter))
                .filter(v -> statusFilter.isEmpty() || statusFilter.contains(v.row.status()))
                .filter(v -> phaseFilter == null || phaseFilter.equals(v.phase))
                .filter(v -> expiring == null || (v.inForce() && v.daysRemaining != null && v.daysRemaining <= expiring))
                .collect(Collectors.toCollection(ArrayList::new));
        if (pattern != null && !views.isEmpty()) {
            // ids were authorised above; the text query can only narrow them.
            Set<String> hit = new HashSet<>(contracts.findIdsMatchingText(
                    views.stream().map(v -> v.row.id()).toList(), pattern));
            views.removeIf(v -> !hit.contains(v.row.id()));
        }
        Comparator<View> byEnd = Comparator.comparing((View v) -> v.lease == null ? LocalDate.MAX : v.lease.endExclusive);
        switch (order) {
            case "LEASE_END_ASC" -> views.sort(byEnd.thenComparing(v -> v.row.contractNumber()));
            case "LEASE_END_DESC" -> views.sort(byEnd.reversed().thenComparing(v -> v.row.contractNumber()));
            default -> { /* repository order is createdAt desc */ }
        }
        int from = Math.min((pageNo - 1) * pageSize, views.size());
        List<View> slice = views.subList(from, Math.min(from + pageSize, views.size()));
        Map<String, String> labels = propertyLabels(slice, today);
        List<Map<String, Object>> items = slice.stream().map(v -> {
            Map<String, Object> m = ref(v);
            m.put("property", labels.get(v.row.id()));
            return m;
        }).toList();
        Map<String, Object> filters = obj("role", roleFilter, "statuses", statusFilter.stream().map(Enum::name).sorted().toList(),
                "leasePhase", phaseFilter, "expiringWithinDays", expiring, "text", text == null || text.isBlank() ? null : "provided",
                "sort", order);
        return obj("meta", meta(), "complete", all.size() <= ROW_CAP, "total", (long) views.size(), "page", pageNo,
                "size", pageSize, "hasMore", from + slice.size() < views.size(), "items", items, "filters", filters);
    }

    // ------------------------------------------------------------------ one contract

    @Transactional(readOnly = true)
    public Map<String, Object> detail(String userId, String contractId) {
        Access a = access(userId, contractId);
        LocalDate today = time.today();
        Lease lease = lease(a.contract, a.rental);
        Resolved cur = resolve(a.contract, today, null);
        ContractRevision rev = cur.revision;
        Map<String, Object> property = project(map(rev.getPropertySnapshot()), PROPERTY_KEYS);
        String phase = phase(a.contract.getStatus(), lease, today);
        Map<String, Object> out = obj("meta", meta(), "contractId", a.contract.getId(),
                "contractNumber", a.contract.getContractNumber(), "askerRole", a.role,
                "status", a.contract.getStatus().name(), "leasePhase", phase,
                "flags", flags(a.contract, phase));
        if (a.contract.getStatus() == ContractStatus.ACTIVE && "LEASE_ENDED".equals(phase))
            out.put("statusSyncPending", true);
        out.put("property", property);
        out.put("counterparty", obj("role", "LANDLORD".equals(a.role) ? "TENANT" : "LANDLORD",
                "name", text(map(("LANDLORD".equals(a.role) ? rev.getTenantSnapshot() : rev.getLandlordSnapshot())), "fullName")));
        out.put("lease", leaseView(lease, today));
        out.put("signing", signing(a.contract));
        out.put("applicableDocument", applicable(cur));
        out.put("createdAt", a.contract.getCreatedAt());
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> terms(String userId, String contractId, List<String> groups, String asOf) {
        Access a = access(userId, contractId);
        LocalDate today = time.today();
        LocalDate date = asOf == null || asOf.isBlank() ? today : date(asOf, today);
        Set<String> wanted = new LinkedHashSet<>();
        if (groups == null || groups.isEmpty()) wanted.addAll(List.of("LEASE", "PRICING", "CHARGES"));
        else for (String g : groups) {
            String key = g == null ? "" : g.trim().toUpperCase(Locale.ROOT);
            if (!TERM_GROUPS.contains(key)) throw invalid();
            wanted.add(key);
        }
        Resolved cur = resolve(a.contract, date, date.isBefore(today) ? date : null);
        ContractRevision r = cur.revision;
        Map<String, Object> g = new LinkedHashMap<>();
        for (String name : wanted) {
            switch (name) {
                case "LEASE" -> { Map<String, Object> l = project(map(r.getLeaseSnapshot()),
                        List.of("startDateText", "endDateText", "durationMonths", "durationText", "handoverDateText"));
                    l.put("computed", leaseView(lease(a.contract, a.rental), today)); g.put(name, l); }
                case "PRICING" -> g.put(name, project(map(r.getFinancialSnapshot()), List.of("amountValue", "paymentCycle",
                        "paymentDueDay", "paymentDueOffsetDays", "paymentMethod", "depositAmountValue", "depositDescription")));
                case "CHARGES" -> g.put(name, projectRows(jsonRows(r.getChargesSnapshot()), List.of("name", "chargeType",
                        "billingMethod", "includedInRent", "unitAmount", "estimatedMonthlyAmount", "note"), 30));
                case "EQUIPMENT" -> g.put(name, projectRows(jsonRows(r.getEquipmentSnapshot()), List.of("name", "quantity",
                        "condition", "note"), 60));
                case "AMENITIES" -> g.put(name, projectRows(jsonRows(r.getAmenitiesSnapshot()), List.of("name", "code"), 60));
                case "POLICIES" -> g.put(name, capStrings(map(r.getPoliciesSnapshot()), 600));
                case "SPECIAL_TERMS" -> g.put(name, cap(r.getSpecialTerms(), 4000));
                case "PROPERTY" -> g.put(name, project(map(r.getPropertySnapshot()), PROPERTY_KEYS));
                case "OCCUPANCY" -> g.put(name, project(map(r.getTenantSnapshot()),
                        List.of("occupantCount", "motorbikeCount", "carCount")));
                default -> throw invalid();
            }
        }
        List<String> warnings = new ArrayList<>(cur.warnings);
        ContractAmendment next = amendmentList(a.contract).stream()
                .filter(x -> Set.of("SCHEDULED", "APPLIED").contains(x.getStatus()) && x.getSignedAt() != null
                        && x.getEffectiveOn().isAfter(date)).min(Comparator.comparing(ContractAmendment::getEffectiveOn)).orElse(null);
        return obj("meta", meta(), "contractId", a.contract.getId(), "contractNumber", a.contract.getContractNumber(),
                "askerRole", a.role, "asOf", date.toString(),
                "asOfKind", date.isBefore(today) ? "PAST" : date.isAfter(today) ? "FUTURE" : "TODAY",
                "applicableDocument", applicable(cur),
                "nextSignedChange", next == null ? null : obj("amendmentId", next.getId(), "effectiveOn", next.getEffectiveOn().toString()),
                "groups", g, "warnings", warnings);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> amendments(String userId, String contractId) {
        Access a = access(userId, contractId);
        LocalDate today = time.today();
        List<ContractAmendment> history = amendmentList(a.contract);
        Optional<ContractAmendment> current = ContractTermsResolver.selectAmendment(history, today);
        // Rows are addressed to a party when created; drafts live in a separate table and are never read here.
        List<ContractAmendment> visible = history.stream()
                .filter(x -> userId.equals(x.getProposerId()) || userId.equals(x.getRecipientId())).toList();
        List<Map<String, Object>> items = new ArrayList<>();
        for (ContractAmendment x : visible.stream().limit(20).toList()) {
            Map<String, Object> m = obj("amendmentId", x.getId(), "status", x.getStatus(),
                    "state", amendmentState(x, current.orElse(null), today),
                    "proposedBy", x.getProposerId().equals(a.contract.getLandlordId()) ? "LANDLORD" : "TENANT",
                    "askerIsProposer", userId.equals(x.getProposerId()),
                    "effectiveOn", x.getEffectiveOn().toString(), "submittedAt", x.getSubmittedAt(),
                    "signedAt", x.getSignedAt(), "appliedAt", x.getAppliedAt(), "respondBy", x.getRespondBy(),
                    "signBy", x.getSignBy(), "reason", cap(x.getReason(), 600));
            m.put("changes", comparison(x));
            items.add(m);
        }
        return obj("meta", meta(), "contractId", a.contract.getId(), "contractNumber", a.contract.getContractNumber(),
                "askerRole", a.role, "currentAmendmentId", current.map(ContractAmendment::getId).orElse(null),
                "total", (long) visible.size(), "hasMore", visible.size() > 20, "items", items);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> lifecycle(String userId, String contractId) {
        Access a = access(userId, contractId);
        Contract c = a.contract;
        Instant now = time.now();
        LocalDate today = time.today();
        Lease lease = lease(c, a.rental);
        List<ContractAmendment> history = amendmentList(c);
        List<ContractRenewal> renewalList = renewals.findByContractIdOrderByRequestedAtDesc(c.getId());
        Map<String, Object> out = obj("meta", meta(), "contractId", c.getId(), "contractNumber", c.getContractNumber(),
                "askerRole", a.role, "status", c.getStatus().name(), "leasePhase", phase(c.getStatus(), lease, today),
                "lease", leaseView(lease, today));
        if (lease != null) {
            Instant opens = ContractLeaseCalc.renewalOpens(lease.endExclusive);
            Instant signBy = ContractLeaseCalc.renewalSignBy(lease.endExclusive);
            List<String> blockers = new ArrayList<>();
            if (!"TENANT".equals(a.role) && !c.getTenantId().equals(userId)) blockers.add("ONLY_TENANT_MAY_REQUEST");
            if (c.getStatus() != ContractStatus.ACTIVE) blockers.add("CONTRACT_NOT_ACTIVE");
            if (now.isBefore(opens)) blockers.add("WINDOW_NOT_OPEN_YET");
            if (!now.isBefore(signBy)) blockers.add("WINDOW_CLOSED");
            if (history.stream().anyMatch(x -> pendingAmendment(x, now, today))) blockers.add("AMENDMENT_IN_PROGRESS");
            if (terminationActive(c)) blockers.add("TERMINATION_IN_PROGRESS");
            if (renewalList.stream().anyMatch(r -> lease.endExclusive.equals(r.getStartsOn())
                    && !"EXPIRED".equals(effectiveRenewalStatus(r, c, now)))) blockers.add("RENEWAL_ALREADY_REQUESTED");
            out.put("renewal", obj("windowOpensAt", opens, "mustBeSignedBefore", signBy,
                    "askerMayRequestNow", blockers.isEmpty(), "blockers", blockers, "advisory", true));
        }
        out.put("renewals", renewalList.stream().limit(10).map(r -> {
            String st = effectiveRenewalStatus(r, c, now);
            return obj("status", r.getStatus(), "effectiveStatus", st, "months", r.getMonths(),
                    "startsOn", r.getStartsOn() == null ? null : r.getStartsOn().toString(),
                    "endExclusive", r.getEndExclusive() == null ? null : r.getEndExclusive().toString(),
                    "requestedAt", r.getRequestedAt(), "respondBy", r.getRespondBy(), "signBy", r.getSignBy(),
                    "appliedAt", r.getAppliedAt(), "extendsLease", "APPLIED".equals(st));
        }).toList());
        out.put("termination", obj("state", terminationState(c), "proposedAt", c.getTerminationProposedAt(),
                "acceptedAt", c.getTerminationAcceptedAt(), "declinedAt", c.getTerminationDeclinedAt(),
                "cancelledAt", c.getTerminationCancelledAt(), "completedAt", c.getTerminationCompletedAt()));
        ContractClosure close = closures.findById(c.getId()).orElse(null);
        out.put("closure", close == null ? null : closureView(close));
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> financial(String userId, String contractId, String invoiceFilter, Integer limit) {
        Access a = access(userId, contractId);
        Contract c = a.contract;
        String filter = invoiceFilter == null || invoiceFilter.isBlank() ? "ALL" : invoiceFilter.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("ALL", "OUTSTANDING", "PAID").contains(filter)) throw invalid();
        int max = limit == null ? 12 : limit;
        if (max < 1 || max > 36) throw invalid();
        boolean landlord = userId.equals(c.getLandlordId());

        List<MonthlyInvoice> all = invoices.findByContractIdOrderByPeriodIndexDesc(c.getId());
        Set<String> paymentIds = new HashSet<>();
        all.forEach(i -> { if (i.getPaymentRequestId() != null) paymentIds.add(i.getPaymentRequestId()); });
        DepositRecord deposit = deposits.findByRentalRequestId(c.getRentalRequestId()).orElse(null);
        var onboardingPayments=payments.findByRentalRequestIdAndTypeIn(c.getRentalRequestId(),
                List.of(com.hs.payment.model.constant.PaymentType.INITIAL,com.hs.payment.model.constant.PaymentType.RESERVATION_DEPOSIT));
        onboardingPayments.forEach(p->paymentIds.add(p.getId()));
        ContractClosure close = closures.findById(c.getId()).orElse(null);
        if (deposit != null) { addIfPresent(paymentIds, deposit.getInitialPaymentRequestId()); addIfPresent(paymentIds, deposit.getRefundPaymentRequestId()); }
        if (close != null) { addIfPresent(paymentIds, close.getRefundPaymentId()); addIfPresent(paymentIds, close.getBalancePaymentId()); }
        Map<String, PaymentRequest> pay = payments.findAllById(paymentIds).stream()
                .collect(Collectors.toMap(PaymentRequest::getId, Function.identity()));

        BigDecimal outstanding = BigDecimal.ZERO, overdue = BigDecimal.ZERO, awaiting = BigDecimal.ZERO, paid = BigDecimal.ZERO;
        long outstandingCount = 0, overdueCount = 0, draftCount = 0;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (MonthlyInvoice i : all) {
            if (i.getStatus() == MonthlyInvoiceStatus.DRAFT) {
                draftCount++;
                if (!landlord) continue; // drafts are the landlord's working state
            }
            PaymentRequest p = i.getPaymentRequestId() == null ? null : pay.get(i.getPaymentRequestId());
            boolean confirmed = p != null && p.getStatus() == PaymentStatus.CONFIRMED;
            boolean issued = i.getStatus() != MonthlyInvoiceStatus.DRAFT;
            boolean effectivelyPaid = issued && (i.getStatus() == MonthlyInvoiceStatus.PAID || confirmed);
            boolean rolled = i.getStatus() == MonthlyInvoiceStatus.ROLLED_OVER;
            boolean open = issued && !effectivelyPaid && !rolled;
            BigDecimal total = i.getTotalAmount() == null ? BigDecimal.ZERO : i.getTotalAmount();
            if (open) {
                outstanding = outstanding.add(total); outstandingCount++;
                if (i.getStatus() == MonthlyInvoiceStatus.OVERDUE) { overdue = overdue.add(total); overdueCount++; }
                if (p != null && p.getStatus() == PaymentStatus.TRANSFER_REPORTED) awaiting = awaiting.add(total);
            } else if (effectivelyPaid) paid = paid.add(total);
            if ("OUTSTANDING".equals(filter) && !open) continue;
            if ("PAID".equals(filter) && !effectivelyPaid) continue;
            if (rows.size() >= max) continue;
            Map<String, Object> m = obj("invoiceId", i.getId(), "periodIndex", i.getPeriodIndex(),
                    "kind", i.isFinalCostSupplement() ? "FINAL_COST_SUPPLEMENT" : "REGULAR", "status", i.getStatus().name(),
                    "issued", issued, "periodStart", i.getPeriodStart().toString(),
                    "periodEndExclusive", i.getPeriodEndExclusive().toString(),
                    "rentCoverageStart", i.getRentPeriodStart() == null ? null : i.getRentPeriodStart().toString(),
                    "rentCoverageEndExclusive", i.getRentPeriodEndExclusive() == null ? null : i.getRentPeriodEndExclusive().toString());
            if (issued) {
                m.put("issuedAt", i.getIssuedAt()); m.put("dueAt", i.getDueAt()); m.put("paidAt", i.getPaidAt());
                m.put("baseAmount", money(i.getBaseAmount())); m.put("lateFeeAmount", money(i.getLateFeeAmount()));
                m.put("totalAmount", money(i.getTotalAmount()));
                m.put("paymentState", paymentState(p, effectivelyPaid, rolled));
                m.put("statusSyncPending", confirmed && i.getStatus() != MonthlyInvoiceStatus.PAID);
                m.put("rolledOver", rolled);
                m.put("lines", invoiceLines(i.getLineItemsSnapshot()));
            }
            rows.add(m);
        }
        Map<String, Object> out = obj("meta", meta(), "contractId", c.getId(), "contractNumber", c.getContractNumber(),
                "askerRole", a.role, "currency", "VND");
        out.put("totals", obj("outstanding", money(outstanding), "outstandingCount", outstandingCount,
                "overdue", money(overdue), "overdueCount", overdueCount,
                "transferReportedAwaitingConfirmation", money(awaiting), "paidConfirmed", money(paid),
                "notIssuedDraftCount", landlord ? draftCount : null,
                "note", "Hóa đơn đã chuyển nợ sang kỳ sau không được cộng lại."));
        out.put("invoices", rows);
        out.put("initialPayment", deposit == null ? null : paymentView(pay.get(deposit.getInitialPaymentRequestId())));
        out.put("onboardingPayments",onboardingPayments.stream().map(this::paymentView).toList());
        out.put("deposit", deposit == null ? null : obj("status", deposit.getStatus().name(),
                "original", money(deposit.getOriginalAmount()), "held", money(deposit.getHeldAmount()),
                "retained", money(deposit.getRetainedAmount()), "refundable", money(deposit.getRefundableAmount()),
                "receivedConfirmedAt", deposit.getReceivedConfirmedAt(), "retainedReason", cap(deposit.getRetainedReason(), 300),
                "refundPayment", paymentView(deposit.getRefundPaymentRequestId() == null ? null : pay.get(deposit.getRefundPaymentRequestId()))));
        if (close != null) {
            Map<String, Object> cv = closureView(close);
            cv.put("refundPayment", paymentView(close.getRefundPaymentId() == null ? null : pay.get(close.getRefundPaymentId())));
            cv.put("balancePayment", paymentView(close.getBalancePaymentId() == null ? null : pay.get(close.getBalancePaymentId())));
            cv.put("depositRefundState", depositRefundState(close, close.getRefundPaymentId() == null ? null : pay.get(close.getRefundPaymentId())));
            out.put("settlement", cv);
        } else out.put("settlement", null);
        return out;
    }

    // ------------------------------------------------------------------ internals

    private record Lease(LocalDate start, LocalDate endExclusive, int months) {}

    private record Access(Contract contract, RentalRequest rental, String role) {}

    private record Resolved(ContractRevision revision, ContractAmendment amendment, List<String> warnings) {}

    private final class View {
        final ContractLeaseRow row; final String primaryRole; final boolean landlord, tenant;
        final Lease lease; final String phase; final Long daysRemaining;
        View(ContractLeaseRow row, String userId, LocalDate today) {
            this.row = row;
            landlord = userId.equals(row.landlordId());
            tenant = userId.equals(row.tenantId()) && row.status() != ContractStatus.DRAFT;
            primaryRole = landlord ? "LANDLORD" : "TENANT";
            lease = row.moveInDate() == null || (row.effectiveLeaseMonths() == null && row.requestedLeaseMonths() == null) ? null
                    : new Lease(row.moveInDate(), ContractLeaseCalc.end(row.moveInDate(),
                    ContractLeaseCalc.months(row.effectiveLeaseMonths(), row.requestedLeaseMonths())),
                    ContractLeaseCalc.months(row.effectiveLeaseMonths(), row.requestedLeaseMonths()));
            phase = AiContractQueryService.this.phase(row.status(), lease, today);
            daysRemaining = lease != null && "IN_LEASE".equals(phase) ? ChronoUnit.DAYS.between(today, lease.endExclusive) : null;
        }
        boolean hasRole(String role) { return "LANDLORD".equals(role) ? landlord : tenant; }
        boolean inForce() { return row.status() == ContractStatus.ACTIVE && "IN_LEASE".equals(phase); }
    }

    private View view(ContractLeaseRow r, String userId, LocalDate today) { return new View(r, userId, today); }

    private List<ContractLeaseRow> rows(String userId) {
        return contracts.findLeaseRowsForUser(userId, PageRequest.of(0, ROW_CAP + 1));
    }

    private Access access(String userId, String contractId) {
        requireUser(userId);
        if (contractId == null || !contractId.matches("[0-9a-fA-F-]{36}")) throw notFound();
        Contract c = contracts.findById(contractId).orElseThrow(AiContractQueryService::notFound);
        boolean landlord = userId.equals(c.getLandlordId());
        boolean tenant = userId.equals(c.getTenantId()) && c.getStatus() != ContractStatus.DRAFT;
        // Same answer for "missing" and "not yours": no existence oracle.
        if (c.getParentContractId() != null || !Boolean.TRUE.equals(c.getActive()) || !(landlord || tenant)) throw notFound();
        RentalRequest rental = rentals.findById(c.getRentalRequestId()).orElse(null);
        return new Access(c, rental, landlord ? "LANDLORD" : "TENANT");
    }

    private Lease lease(Contract c, RentalRequest rental) {
        if (rental == null || rental.getMoveInDate() == null) return null;
        if (c.getEffectiveLeaseMonths() == null && rental.getLeaseMonths() == null) return null;
        int months = ContractLeaseCalc.months(c, rental);
        return new Lease(rental.getMoveInDate(), ContractLeaseCalc.end(rental.getMoveInDate(), months), months);
    }

    private Map<String, Object> leaseView(Lease l, LocalDate today) {
        if (l == null) return obj("known", false);
        return obj("known", true, "start", l.start.toString(), "endExclusive", l.endExclusive.toString(),
                "lastRentalDay", l.endExclusive.minusDays(1).toString(), "months", l.months,
                "daysRemainingIncludingToday", today.isBefore(l.start) ? null : today.isBefore(l.endExclusive)
                        ? ChronoUnit.DAYS.between(today, l.endExclusive) : 0L,
                "daysUntilStart", today.isBefore(l.start) ? ChronoUnit.DAYS.between(today, l.start) : 0L);
    }

    String phase(ContractStatus status, Lease l, LocalDate today) {
        return switch (status) {
            case DRAFT, PENDING_REVIEW, LANDLORD_SIGNATURE_PENDING, TENANT_SIGNATURE_PENDING -> "NOT_SIGNED";
            case CANCELLED -> "CANCELLED";
            case TERMINATED -> "TERMINATED";
            case CLOSED -> "CLOSED";
            case EXPIRED -> "LEASE_ENDED";
            case ACTIVE -> l == null ? "UNKNOWN" : today.isBefore(l.start) ? "NOT_STARTED"
                    : today.isBefore(l.endExclusive) ? "IN_LEASE" : "LEASE_ENDED";
        };
    }

    private Map<String, Object> flags(Contract c, String phase) {
        boolean signed = c.getSignedAt() != null || Set.of(ContractStatus.ACTIVE, ContractStatus.EXPIRED,
                ContractStatus.CLOSED, ContractStatus.TERMINATED).contains(c.getStatus());
        return obj("fullySigned", signed, "inForce", c.getStatus() == ContractStatus.ACTIVE && "IN_LEASE".equals(phase),
                "inLeasePeriod", "IN_LEASE".equals(phase), "leaseEnded", "LEASE_ENDED".equals(phase),
                "terminated", c.getStatus() == ContractStatus.TERMINATED, "settlementCompleted", c.getStatus() == ContractStatus.CLOSED);
    }

    private Map<String, Object> signing(Contract c) {
        String pending = pendingSigner(c.getStatus());
        return obj("status", c.getStatus().name(), "waitingFor", pending, "signedAt", c.getSignedAt(),
                "landlordConfirmedAt", c.getLandlordConfirmedAt());
    }

    private static String pendingSigner(ContractStatus s) {
        return s == ContractStatus.LANDLORD_SIGNATURE_PENDING ? "LANDLORD" : s == ContractStatus.TENANT_SIGNATURE_PENDING ? "TENANT" : null;
    }

    private List<ContractAmendment> amendmentList(Contract c) { return amendments.findByContractIdOrderByCreatedAtDesc(c.getId()); }

    /** Terms valid on {@code date}; {@code knownBy} (past dates only) rejects amendments signed after that day. */
    private Resolved resolve(Contract c, LocalDate date, LocalDate pastDate) {
        List<ContractAmendment> history = amendmentList(c);
        List<String> warnings = new ArrayList<>();
        List<ContractAmendment> usable = history;
        if (pastDate != null) {
            Instant knownUntil = pastDate.plusDays(1).atStartOfDay(BillingTime.ZONE).toInstant();
            usable = history.stream().filter(x -> x.getSignedAt() == null || x.getSignedAt().isBefore(knownUntil)).toList();
            if (history.stream().anyMatch(x -> x.getSignedAt() != null && !x.getSignedAt().isBefore(knownUntil)
                    && !x.getEffectiveOn().isAfter(pastDate) && Set.of("SCHEDULED", "APPLIED").contains(x.getStatus())))
                warnings.add("RETROACTIVE_AMENDMENT_SIGNED_AFTER_DATE");
            if (c.getSignedAt() != null && c.getSignedAt().isAfter(knownUntil)) warnings.add("DATE_BEFORE_CONTRACT_SIGNED");
            warnings.add("PAST_VIEW_RECONSTRUCTED_FROM_SIGNED_SNAPSHOTS");
        }
        Optional<ContractAmendment> pick = ContractTermsResolver.selectAmendment(usable, date);
        String revisionId = pick.map(ContractAmendment::getTermsRevisionId).orElse(c.getCurrentRevisionId());
        if (revisionId == null) throw new AppException(ContractErrorCode.CONTRACT_REVISION_NOT_FOUND);
        ContractRevision rev = revisions.findById(revisionId)
                .orElseThrow(() -> new AppException(ContractErrorCode.CONTRACT_REVISION_NOT_FOUND));
        return new Resolved(rev, pick.orElse(null), warnings);
    }

    private Map<String, Object> applicable(Resolved r) {
        return obj("source", r.amendment == null ? "ORIGINAL_CONTRACT" : "AMENDMENT", "amendmentId",
                r.amendment == null ? null : r.amendment.getId(), "revisionId", r.revision.getId(),
                "revisionNumber", r.revision.getRevisionNumber(),
                "effectiveFrom", r.amendment == null ? null : r.amendment.getEffectiveOn().toString(),
                "signedAt", r.amendment == null ? null : r.amendment.getSignedAt());
    }

    private String amendmentState(ContractAmendment x, ContractAmendment current, LocalDate today) {
        String s = x.getStatus();
        if (Set.of("SCHEDULED", "APPLIED").contains(s) && x.getSignedAt() != null) {
            if (x.getEffectiveOn().isAfter(today)) return "SIGNED_PENDING_EFFECTIVE";
            return current != null && current.getId().equals(x.getId()) ? "CURRENT" : "REPLACED_BY_LATER_AMENDMENT";
        }
        return switch (s) {
            case "REQUESTED", "CHANGES_REQUESTED", "TENANT_REQUESTED", "TENANT_ACCEPTED" -> "IN_PROGRESS";
            case "APPROVED" -> "AWAITING_SIGNATURES";
            case "SUPERSEDED" -> "SUPERSEDED";
            case "REJECTED", "TENANT_REJECTED" -> "REJECTED";
            case "WITHDRAWN" -> "WITHDRAWN";
            case "EXPIRED", "TENANT_EXPIRED" -> "EXPIRED";
            case "TENANT_FULFILLED" -> "ANSWERED_BY_NEW_PROPOSAL";
            default -> "OTHER";
        };
    }

    private boolean pendingAmendment(ContractAmendment x, Instant now, LocalDate today) {
        if (!ContractAmendmentService.PENDING.contains(x.getStatus())) return false;
        if (Set.of("TENANT_REQUESTED", "TENANT_ACCEPTED").contains(x.getStatus())
                && x.getRespondBy() != null && !now.isBefore(x.getRespondBy())) return false; // would be expired by sync
        return !("SCHEDULED".equals(x.getStatus()) && !x.getEffectiveOn().isAfter(today)); // already economically applied
    }

    private List<Map<String, Object>> comparison(ContractAmendment x) {
        try {
            List<Map<String, Object>> rows = mapper.readValue(x.getComparisonSnapshot(), new TypeReference<>() {});
            return projectRows(rows, List.of("label", "before", "after"), 30);
        } catch (Exception e) { return List.of(); }
    }

    private String effectiveRenewalStatus(ContractRenewal r, Contract c, Instant now) {
        boolean pending = Set.of("REQUESTED", "APPROVED").contains(r.getStatus());
        if (pending && c.getStatus() != ContractStatus.ACTIVE) return "EXPIRED";
        if ("REQUESTED".equals(r.getStatus()) && r.getRespondBy() != null && !now.isBefore(r.getRespondBy())) return "EXPIRED";
        if ("APPROVED".equals(r.getStatus()) && r.getSignBy() != null && !now.isBefore(r.getSignBy())) return "EXPIRED";
        return r.getStatus();
    }

    private static boolean terminationActive(Contract c) {
        return c.getTerminationProposedAt() != null && c.getTerminationCancelledAt() == null
                && c.getTerminationDeclinedAt() == null && c.getTerminationCompletedAt() == null;
    }

    private static String terminationState(Contract c) {
        if (c.getTerminationCompletedAt() != null) return "COMPLETED";
        if (c.getTerminationForcedAt() != null) return "FORCED";
        if (c.getTerminationCancelledAt() != null) return "CANCELLED";
        if (c.getTerminationDeclinedAt() != null) return "DECLINED";
        if (c.getTerminationAcceptedAt() != null) return "ACCEPTED";
        if (c.getTerminationProposedAt() != null) return "PROPOSED";
        return "NONE";
    }

    private Map<String, Object> closureView(ContractClosure s) {
        return obj("status", s.getStatus(), "proposedAt", s.getProposedAt(), "respondBy", s.getRespondBy(),
                "responseOverdueAt", s.getResponseOverdueAt(), "acceptedAt", s.getAcceptedAt(),
                "refundAmount", money(s.getRefundAmount()), "balanceAmountOwedByTenant", money(s.getBalanceAmount()),
                "deductionTotal", money(s.getOtherDeduction()), "utilityAdjustment", money(s.getUtilityAdjustment()),
                "refundDueAt", s.getRefundDueAt(), "closedAt", s.getClosedAt());
    }

    private static String depositRefundState(ContractClosure s, PaymentRequest refund) {
        if (s.getClosedAt() != null) return "SETTLED_AND_CLOSED";
        if (refund != null) return switch (refund.getStatus()) {
            case CONFIRMED -> "REFUND_CONFIRMED_BY_RECIPIENT";
            case TRANSFER_REPORTED -> "REFUND_TRANSFER_REPORTED_AWAITING_RECIPIENT_CONFIRMATION";
            case DISPUTED -> "REFUND_DISPUTED";
            case OVERDUE -> "REFUND_OVERDUE";
            default -> "REFUND_PENDING_TRANSFER";
        };
        if (s.getAcceptedAt() != null) return "SETTLEMENT_AGREED_PAYMENT_NOT_CREATED";
        return "SETTLEMENT_NOT_AGREED";
    }

    private static String paymentState(PaymentRequest p, boolean paid, boolean rolled) {
        if (rolled) return "ROLLED_OVER_TO_LATER_INVOICE";
        if (paid) return "PAID_CONFIRMED";
        if (p == null) return "NO_PAYMENT_REQUEST";
        return switch (p.getStatus()) {
            case TRANSFER_REPORTED -> "TRANSFER_REPORTED_AWAITING_CONFIRMATION";
            case AWAITING_TRANSFER -> "AWAITING_TRANSFER";
            case REJECTED -> "TRANSFER_REJECTED";
            case DISPUTED -> "DISPUTED";
            case OVERDUE -> "OVERDUE";
            default -> p.getStatus().name();
        };
    }

    private Map<String, Object> paymentView(PaymentRequest p) {
        if (p == null) return null;
        return obj("status", p.getStatus().name(), "type", p.getType() == null ? null : p.getType().name(),
                "amount", money(p.getTotalAmount()), "dueAt", p.getDueAt(), "payerReportedAt", p.getPayerReportedAt(),
                "payeeConfirmedAt", p.getPayeeConfirmedAt(), "confirmedAt", p.getConfirmedAt(),
                "recipientConfirmed", p.getStatus() == PaymentStatus.CONFIRMED);
    }

    private List<Map<String, Object>> invoiceLines(String json) {
        try {
            List<com.hs.contract.dto.billing.InvoiceLine> lines = mapper.readValue(json == null ? "[]" : json,
                    new TypeReference<List<com.hs.contract.dto.billing.InvoiceLine>>() {});
            return lines.stream().limit(12).map(l -> obj("type", l.type(), "description", cap(l.description(), 200),
                    "quantity", money(l.quantity()), "unitPrice", money(l.unitPrice()), "amount", money(l.amount()),
                    "quantityUnit", l.quantityUnit())).toList();
        } catch (Exception e) { return List.of(); }
    }

    private Map<String, String> propertyLabels(List<View> slice, LocalDate today) {
        if (slice.isEmpty()) return Map.of();
        List<String> ids = slice.stream().map(v -> v.row.id()).toList();
        Map<String, List<ContractAmendment>> byContract = amendments.findByContractIdInOrderByCreatedAtDesc(ids).stream()
                .collect(Collectors.groupingBy(ContractAmendment::getContractId));
        Map<String, String> revisionOf = new HashMap<>();
        for (View v : slice) {
            revisionOf.put(v.row.id(), ContractTermsResolver.selectAmendment(byContract.getOrDefault(v.row.id(), List.of()), today)
                    .map(ContractAmendment::getTermsRevisionId).orElse(v.row.currentRevisionId()));
        }
        Map<String, ContractRevision> revs = revisions.findAllById(
                revisionOf.values().stream().filter(Objects::nonNull).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(ContractRevision::getId, Function.identity()));
        Map<String, String> labels = new HashMap<>();
        revisionOf.forEach((cid, rid) -> {
            ContractRevision r = rid == null ? null : revs.get(rid);
            if (r != null) labels.put(cid, text(map(r.getPropertySnapshot()), "fullAddress"));
        });
        return labels;
    }

    private Map<String, Object> ref(View v) {
        return obj("contractId", v.row.id(), "contractNumber", v.row.contractNumber(), "askerRole", v.primaryRole,
                "status", v.row.status().name(), "leasePhase", v.phase,
                "leaseStart", v.lease == null ? null : v.lease.start.toString(),
                "leaseEndExclusive", v.lease == null ? null : v.lease.endExclusive.toString(),
                "lastRentalDay", v.lease == null ? null : v.lease.endExclusive.minusDays(1).toString(),
                "daysRemainingIncludingToday", v.daysRemaining, "waitingForSignature", pendingSigner(v.row.status()));
    }

    private Map<String, Object> meta() {
        Instant real = Instant.now();
        Instant business = time.now();
        return obj("readAt", real, "businessNow", business, "businessDate", time.today().toString(),
                "timezone", BillingTime.ZONE.getId(),
                "simulatedBusinessClock", Math.abs(Duration.between(real, business).getSeconds()) > 5,
                "dateRule", "Ngày kết thúc không bao gồm; ngày thuê cuối là ngày liền trước.");
    }

    // ------------------------------------------------------------------ small helpers

    private Map<String, Object> map(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try { return mapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {}); }
        catch (Exception e) { return new LinkedHashMap<>(); }
    }

    private List<Map<String, Object>> jsonRows(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return mapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {}); }
        catch (Exception e) { return List.of(); }
    }

    private static Map<String, Object> project(Map<String, Object> source, List<String> keys) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : keys) if (source.containsKey(k) && source.get(k) != null) out.put(k, scalar(source.get(k)));
        return out;
    }

    private static List<Map<String, Object>> projectRows(List<Map<String, Object>> rows, List<String> keys, int limit) {
        return rows.stream().limit(limit).map(r -> project(r, keys)).collect(Collectors.toCollection(ArrayList::new));
    }

    private static Map<String, Object> capStrings(Map<String, Object> source, int max) {
        Map<String, Object> out = new LinkedHashMap<>();
        source.forEach((k, v) -> { if (v != null && !k.toLowerCase(Locale.ROOT).contains("snapshot")) out.put(k, v instanceof String s ? cap(s, max) : scalar(v)); });
        return out;
    }

    private static Object scalar(Object v) { return v instanceof String s ? cap(s, 600) : v instanceof Number || v instanceof Boolean ? v : String.valueOf(v); }

    private static String text(Map<String, Object> m, String key) { Object v = m.get(key); return v == null ? null : cap(String.valueOf(v), 200); }

    private static String cap(String s, int max) { return s == null ? null : s.length() <= max ? s : s.substring(0, max); }

    private static String money(BigDecimal v) { return v == null ? null : v.toPlainString(); }

    private static void addIfPresent(Set<String> set, String id) { if (id != null) set.add(id); }

    private static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static String role(String role) {
        if (role == null || role.isBlank() || role.equalsIgnoreCase("ANY")) return null;
        String r = role.trim().toUpperCase(Locale.ROOT);
        if (!r.equals("LANDLORD") && !r.equals("TENANT")) throw invalid();
        return r;
    }

    private static int days(Integer v, int dflt) {
        int d = v == null ? dflt : v;
        if (d < 1 || d > 365) throw invalid();
        return d;
    }

    private static LocalDate date(String v, LocalDate today) {
        try {
            LocalDate d = LocalDate.parse(v.trim());
            if (d.isBefore(today.minusYears(10)) || d.isAfter(today.plusYears(10))) throw invalid();
            return d;
        } catch (java.time.format.DateTimeParseException e) { throw invalid(); }
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String v) {
        try { return Enum.valueOf(type, v.trim().toUpperCase(Locale.ROOT)); }
        catch (RuntimeException e) { throw invalid(); }
    }

    private static void requireUser(String userId) {
        if (userId == null || userId.isBlank() || "system".equals(userId))
            throw new AppException(com.hs.common.advice.entity.enums.ErrorCode.UNAUTHENTICATED);
    }

    private static AppException invalid() { return new AppException(ContractErrorCode.CONTRACT_AI_QUERY_INVALID); }

    private static AppException notFound() { return new AppException(ContractErrorCode.CONTRACT_NOT_FOUND); }
}
