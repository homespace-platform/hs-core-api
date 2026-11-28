package com.hs.contract.service;

import com.hs.common.time.BillingTime;
import com.hs.contract.dto.branch.BranchAttentionResponse.Priority;
import com.hs.contract.dto.branch.BranchOverviewResponse.Clock;
import com.hs.contract.dto.branch.BranchOverviewResponse.ExpiryStatus;
import com.hs.contract.dto.tenant.TenantDashboardResponse;
import com.hs.contract.dto.tenant.TenantDashboardResponse.*;
import com.hs.contract.model.ContractAmendment;
import com.hs.contract.repository.TenantDashboardReadRepository;
import com.hs.contract.repository.TenantDashboardReadRepository.*;
import com.hs.listing.model.constant.AppointmentStatus;
import com.hs.listing.model.constant.ListingEnums.MediaType;
import com.hs.listing.model.constant.ListingStatus;
import com.hs.listing.model.constant.RentalRequestStatus;
import com.hs.payment.model.PaymentRequest;
import com.hs.payment.model.constant.PaymentStatus;
import com.hs.payment.model.constant.PaymentType;
import com.hs.payment.service.PaymentTimePolicy;
import com.hs.storage.config.StorageProperties;
import com.hs.storage.model.constant.StorageVisibility;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * One read-only snapshot of everything a tenant has to look at. Scoped by the verified user id; never syncs, never
 * locks, one batch query per block (no query per lease).
 */
@Service
@RequiredArgsConstructor
public class TenantDashboardService {
    private static final ZoneId ZONE = BillingTime.ZONE;
    static final String DEPOSIT_FIRST = "DEPOSIT_FIRST", RENT = "RENT", UTILITY_OTHER = "UTILITY_OTHER";
    // Detail lists are bounded; monetary totals are aggregated before these caps.
    // shortcut: task/waiting source counts stop at these caps; add pagination if tenants exceed them.
    private static final int MAX_LEASES = 50, MAX_PAYMENTS = 200, MAX_REFUNDS = 50, MAX_TASK_SOURCE = 50,
            MAX_COMPLAINTS = 100, TASKS = 20, PAYABLES = 20, LISTED = 5, OPEN_REQUESTS = 3, HOLDS = 20;
    private static final Set<PaymentStatus> NOT_REPORTED =
            Set.of(PaymentStatus.AWAITING_TRANSFER, PaymentStatus.REJECTED, PaymentStatus.OVERDUE);
    private static final Set<PaymentType> ONBOARDING = Set.of(PaymentType.INITIAL, PaymentType.RESERVATION_DEPOSIT);
    private static final Set<PaymentType> REFUNDS = Set.of(PaymentType.DEPOSIT_REFUND, PaymentType.INITIAL_PAYMENT_REFUND);
    private static final Set<PaymentStatus> REFUND_OPEN =
            Set.of(PaymentStatus.AWAITING_TRANSFER, PaymentStatus.TRANSFER_REPORTED);
    private static final Set<String> AMENDMENT_STATUSES = Set.of("REQUESTED", "CHANGES_REQUESTED", "APPROVED",
            "SCHEDULED", "APPLIED", "TENANT_REQUESTED", "TENANT_ACCEPTED");
    private static final Set<AppointmentStatus> UPCOMING_VISITS =
            EnumSet.of(AppointmentStatus.PENDING, AppointmentStatus.CONFIRMED);
    private static final Duration HOLD_CRITICAL = Duration.ofMinutes(15);

    private final TenantDashboardReadRepository reads;
    private final BillingTime billingTime;
    private final StorageProperties storage;
    private final RentalOnboardingService onboarding;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TenantDashboardResponse summary(String userId, int spendMonths) {
        return summary(userId, spendMonths, Instant.now());
    }

    /** {@code real}: the real clock, for visits and SmartCA (injected so tests are exact). */
    TenantDashboardResponse summary(String userId, int spendMonths, Instant real) {
        Instant now = billingTime.now();
        LocalDate today = LocalDate.ofInstant(now, ZONE);

        var leaseRows = reads.findLeases(userId, PageRequest.of(0, MAX_LEASES));
        var leaseIds = leaseRows.stream().map(LeaseRow::contractId).toList();
        var billingTypes = PaymentTimePolicy.BILLING_TYPES.stream().map(Enum::name).toList();
        var open = new ArrayList<>(reads.findDashboardPayments(userId, now, real, billingTypes, PageRequest.of(0, MAX_PAYMENTS)));
        var nextPayments = reads.findNextPayments(userId, now, real, billingTypes, leaseIds, PageRequest.of(0, MAX_LEASES + 1));
        open.addAll(nextPayments);
        var awaitingPayments = reads.findAwaitingPayments(userId, PageRequest.of(0, LISTED));
        open.addAll(awaitingPayments);
        open = new ArrayList<>(open.stream().collect(Collectors.toMap(PaymentRequest::getId, Function.identity(),
                (a, b) -> a, LinkedHashMap::new)).values());
        var totals = reads.moneyTotals(userId, now, real, billingTypes);
        var refundTotals = reads.refundTotals(userId, REFUNDS, REFUND_OPEN).getFirst();
        var refunds = reads.findIncomingRefunds(userId, REFUNDS, REFUND_OPEN, PageRequest.of(0, MAX_REFUNDS));
        var signing = reads.findSigningContracts(userId, PageRequest.of(0, MAX_TASK_SOURCE));
        var amendments = leaseIds.isEmpty() ? List.<ContractAmendment>of()
                : reads.findAmendments(leaseIds, AMENDMENT_STATUSES);
        var exits = leaseIds.isEmpty() ? List.<ExitRow>of() : reads.findProposedEarlyExits(leaseIds);
        var signatures = reads.findSignaturesToConfirm(userId, real, PageRequest.of(0, MAX_TASK_SOURCE));
        var holdRows = reads.findHolds(userId, now, PageRequest.of(0, HOLDS));
        var holdPayments = holdRows.isEmpty() ? List.<PaymentRequest>of() : reads.findHoldPayments(userId,
                holdRows.stream().map(HoldRow::id).toList(), NOT_REPORTED);
        LocalDate realToday = LocalDate.ofInstant(real, ZONE);
        var visitRows = reads.findUpcomingVisits(userId, UPCOMING_VISITS, realToday, LocalTime.ofInstant(real, ZONE),
                PageRequest.of(0, LISTED));
        long visitCount = reads.countUpcomingVisits(userId, UPCOMING_VISITS, realToday, LocalTime.ofInstant(real, ZONE));
        var requestRows = reads.findOpenRequests(userId,
                EnumSet.of(RentalRequestStatus.PENDING, RentalRequestStatus.ACCEPTED), PageRequest.of(0, OPEN_REQUESTS));
        var counts = requestCounts(reads.countRequestsByStatus(userId));
        YearMonth month = YearMonth.from(now.atZone(ZONE));
        var spendRows = reads.findSpending(userId, windowStart(month, 12),
                month.plusMonths(1).atDay(1).atStartOfDay(ZONE).toInstant()).stream().map(b -> new SpendRow(
                        YearMonth.parse(b.getMonth()).atDay(1).atStartOfDay(ZONE).toInstant(),
                        PaymentType.valueOf(b.getType()), b.getAmount())).toList();
        long pdfIssues = reads.countReceiptPdfIssues(userId, now.minus(90, ChronoUnit.DAYS),
                now.minus(5, ChronoUnit.MINUTES));
        var complaintRows = reads.findOpenComplaints(userId, PageRequest.of(0, MAX_COMPLAINTS));
        var complaintTotals = reads.countComplaints(userId).getFirst();
        var saved = saved(reads.countSavedByListingStatus(userId));
        var closures = reads.findClosures(userId, PageRequest.of(0, MAX_TASK_SOURCE));
        var onboardings = new LinkedHashMap<String, RentalOnboardingService.View>();
        for (String id : reads.findAcceptedRequests(userId, PageRequest.of(0, 10))) {
            onboardings.put(id, onboarding.get(id, userId));
        }
        var depositRows = new ArrayList<>(reads.findDeposits(userId, PageRequest.of(0, MAX_LEASES)));
        depositRows.addAll(reads.findLegacyDeposits(userId, PageRequest.of(0, MAX_LEASES)));
        BigDecimal heldTotal = reads.totalHeldDeposits(userId);
        var deposits = new Deposits(heldTotal, depositRows.stream().limit(MAX_LEASES).map(d -> new Deposit(
                d.contractId(), d.contractNumber(), d.amount(), d.status(), d.closureStatus() == null ? null
                : new Closure(d.closureStatus(), d.refundAmount(), d.refundDueAt()),
                contractPath(d.contractId()) + "?tab=end")).toList());

        var lookup = new Lookup(userId, leaseRows, open, refunds, signing, signatures, complaintRows, holdRows,
                visitRows, requestRows, closures);
        var waiting = waiting(userId, now, amendments, leaseRows, closures, lookup);

        // Money
        var payables = payables(open, refunds, now, real, lookup, totals, refundTotals);

        // Leases
        var leases = new ArrayList<Lease>();
        var ends = new HashMap<String, LocalDate>();
        for (LeaseRow row : leaseRows) {
            LocalDate end = ContractLeaseCalc.end(row.agreedEarlyEnd(), row.agreedEarlyMonths(), row.effectiveMonths(),
                    row.leaseMonths(), row.moveInDate());
            ends.put(row.contractId(), end);
            Long days = end == null ? null : ChronoUnit.DAYS.between(today, end);
            leases.add(new Lease(row.contractId(), row.contractNumber(), row.listingId(), lookup.title(row.listingId()),
                    lookup.address(row.listingId()), lookup.thumbnail(row.listingId()), lookup.landlord(row.landlordId()),
                    row.moveInDate(), end, days, days == null ? null : expiry(days), row.monthlyRent(),
                    payables.byContract().get(row.contractId()), contractPath(row.contractId())));
        }
        leases.sort(Comparator.comparing(Lease::endExclusive, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Lease::contractId));

        // Tasks
        var tasks = tasks(userId, now, real, today, leaseRows, ends, refunds, signing, amendments, exits, signatures,
                complaintRows, closures, onboardings, open, lookup);

        // Holds: only while the money for the hold is still open
        var holds = new ArrayList<Hold>();
        for (HoldRow h : holdRows) {
            BigDecimal due = holdPayments.stream().filter(p -> NOT_REPORTED.contains(p.getStatus())
                            && ONBOARDING.contains(p.getType()) && h.id().equals(p.getRentalRequestId()))
                    .map(PaymentRequest::getTotalAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (due.signum() <= 0) continue;
            long seconds = Math.max(0, Duration.between(now, h.holdExpiresAt()).toSeconds());
            holds.add(new Hold(h.id(), lookup.title(h.listingId()), lookup.thumbnail(h.listingId()), h.holdExpiresAt(),
                    seconds, seconds <= HOLD_CRITICAL.toSeconds(), due, requestPath(h.id())));
        }

        var visits = visitRows.stream().map(v -> new Visit(v.id(), v.listingId(), lookup.title(v.listingId()),
                lookup.address(v.listingId()), lookup.thumbnail(v.listingId()), v.date(), v.startTime(), v.endTime(),
                v.status().name(), Boolean.TRUE.equals(v.rescheduleRequested()), v.proposedDate(),
                v.proposedStartTime())).toList();
        var openRequests = requestRows.stream().map(r -> new OpenRequest(r.id(), lookup.title(r.listingId()),
                lookup.thumbnail(r.listingId()), r.status().name(), r.createdAt(), r.respondBy(), r.holdExpiresAt(),
                requestPath(r.id()))).toList();

        var complaintItems = complaintRows.stream().limit(LISTED).map(c -> new Complaint(c.id(),
                lookup.contract(c.contractId()), c.category(), c.status(), c.respondBy(),
                userId.equals(c.responseDueFor()) ? "YOU" : "OTHER",
                c.rentalRequestId() == null ? null : requestPath(c.rentalRequestId()))).toList();

        return new TenantDashboardResponse(now, real, ZONE.getId(), new Setup(Boolean.TRUE.equals(reads.isOnboarded(userId))),
                new Leases((int) reads.countLeases(userId), List.copyOf(leases)), payables.response(), tasks, List.copyOf(holds),
                new Visits((int) visitCount, visits), new Requests(counts, openRequests),
                spending(spendRows, month, spendMonths, (int) pdfIssues),
                deposits, waiting, new Complaints(((Number) complaintTotals[0]).intValue(),
                ((Number) complaintTotals[1]).intValue(), complaintItems), saved);
    }

    /* ---------- money ---------- */

    private record Money(Payables response, Map<String, Payable> byContract) {}

    private Money payables(List<PaymentRequest> open, List<PaymentRequest> refunds, Instant now,
                           Instant real, Lookup lookup, MoneyTotals totals, Object[] refundTotals) {
        var due = new ArrayList<Payable>();
        var awaiting = new ArrayList<AwaitingItem>();
        for (PaymentRequest p : open) {
            Instant at = at(p, now, real);
            if (p.getStatus() == PaymentStatus.TRANSFER_REPORTED) {
                awaiting.add(new AwaitingItem(p.getId(), lookup.contract(p.getContractId()), p.getTotalAmount(),
                        p.getPayerReportedAt(), p.getConfirmationDueAt(), paymentPath(p)));
            } else if (p.getOpensAt() == null || !p.getOpensAt().isAfter(at)) {
                due.add(payable(p, at, lookup));
            }
        }
        due.sort(Comparator.comparing(Payable::overdue).reversed()
                .thenComparing(Payable::dueAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Payable::paymentId));
        Payable next = due.stream().filter(p -> !p.overdue() && p.dueAt() != null).findFirst().orElse(null);
        var byContract = new HashMap<String, Payable>();
        for (Payable p : due) if (p.contractId() != null && !p.overdue() && p.dueAt() != null)
            byContract.putIfAbsent(p.contractId(), p);

        var refundItems = refunds.stream().limit(LISTED).map(p -> new RefundItem(p.getId(),
                lookup.contract(p.getContractId()), p.getType().name(), p.getStatus().name(), p.getTotalAmount(),
                p.getDueAt(), p.getStatus() == PaymentStatus.TRANSFER_REPORTED ? p.getConfirmationDueAt() : null,
                paymentPath(p))).toList();
        var payables = new Payables((int) totals.getOverdueCount(), totals.getOverdueAmount(), totals.getDueSoonAmount(),
                next, List.copyOf(due.subList(0, Math.min(PAYABLES, due.size()))),
                new Awaiting((int) totals.getAwaitingCount(), totals.getAwaitingAmount(), awaiting.stream().limit(LISTED).toList()),
                new RefundsIncoming(((Number) refundTotals[0]).intValue(), (BigDecimal) refundTotals[1], refundItems));
        return new Money(payables, byContract);
    }

    private Payable payable(PaymentRequest p, Instant at, Lookup lookup) {
        Clock clock = clock(p);
        boolean late = p.getDueAt() != null && p.getDueAt().isBefore(at);
        String reason = p.getRejectedReason();
        return new Payable(p.getId(), p.getType().name(), p.getContractId(), lookup.contract(p.getContractId()),
                lookup.title(p.getListingId()), p.getInvoiceId(), lookup.period(p.getInvoiceId()), p.getTotalAmount(),
                p.getDueAt(), clock, late, p.getStatus() == PaymentStatus.REJECTED && reason != null
                        ? reason.substring(0, Math.min(160, reason.length())) : null, paymentPath(p));
    }

    private static Clock clock(PaymentRequest p) {
        return PaymentTimePolicy.usesBillingTime(p) ? Clock.BILLING : Clock.REAL;
    }

    private Instant at(PaymentRequest p, Instant now, Instant real) {
        return clock(p) == Clock.BILLING ? now : real;
    }

    /* ---------- tasks ---------- */

    private Tasks tasks(String userId, Instant now, Instant real, LocalDate today, List<LeaseRow> leases,
                        Map<String, LocalDate> ends, List<PaymentRequest> refunds, List<SignRow> signing,
                        List<ContractAmendment> amendments, List<ExitRow> exits, List<SigRow> signatures,
                        List<ComplaintRow> complaints, List<com.hs.contract.model.ContractClosure> closures,
                        Map<String, RentalOnboardingService.View> onboardings, List<PaymentRequest> open, Lookup lookup) {
        var found = new LinkedHashMap<String, Task>();
        java.util.function.Consumer<Task> add = t -> {
            Instant at = t.clock() == Clock.BILLING ? now : real;
            // A passed deadline is settled by the system (late party loses), so it is no longer a to-do.
            if (t.dueAt() == null || t.dueAt().isAfter(at)) found.putIfAbsent(t.key(), t);
        };
        for (SignRow s : signing) {
            if (onboardings.containsKey(s.rentalRequestId()) && s.parentId() == null) continue;
            String type = "DEPOSIT".equals(s.agreementKind()) ? "SIGN_DEPOSIT" : s.parentId() != null ? "SIGN_ANNEX" : "SIGN_LEASE";
            String href = switch (type) {
                case "SIGN_DEPOSIT" -> requestPath(s.rentalRequestId());
                case "SIGN_ANNEX" -> "/dashboard/contracts/" + s.id() + "?tab=sign";
                default -> contractPath(s.id()) + "?tab=sign";
            };
            add.accept(task(type, s.id(), s.rentalRequestId(), s.number(), s.dueAt(), Clock.BILLING, now, real, href));
        }
        for (var entry : onboardings.entrySet()) {
            var view = entry.getValue();
            var next = view.nextAction();
            if (next == null || !"TENANT".equals(next.actor())) continue;
            String requestId = entry.getKey();
            String type = switch (next.code()) {
                case SIGN_DEPOSIT_TENANT -> "SIGN_DEPOSIT";
                case SIGN_LEASE_TENANT -> "SIGN_LEASE";
                case PAY_DEPOSIT, PAY_FIRST_RENT, RESPOND_DEFAULT_NOTICE -> next.code().name();
                default -> null; // Refund tasks are derived once from payment truth below.
            };
            if (type == null) continue;
            if (type.startsWith("PAY_") && open.stream().anyMatch(p -> requestId.equals(p.getRentalRequestId())
                    && ONBOARDING.contains(p.getType()) && NOT_REPORTED.contains(p.getStatus())
                    && (p.getOpensAt() == null || !p.getOpensAt().isAfter(at(p, now, real))))) continue;
            var document = signing.stream().filter(s -> requestId.equals(s.rentalRequestId())
                    && ("SIGN_DEPOSIT".equals(type) == "DEPOSIT".equals(s.agreementKind()))).findFirst();
            String contractId = document.map(SignRow::id).orElse(null);
            add.accept(task(type, contractId == null ? requestId : contractId, contractId, requestId,
                    document.map(SignRow::number).orElse(null), next.dueAt(), Clock.BILLING, now, real,
                    requestPath(requestId) + (type.startsWith("SIGN_") ? "&tab=sign" : "&tab=payment")));
        }
        for (var closure : closures) {
            if ("PROPOSED".equals(closure.getStatus())) add.accept(task("CLOSURE_RESPONSE", closure.getContractId(),
                    null, lookup.contract(closure.getContractId()), closure.getRespondBy(), Clock.BILLING, now, real,
                    contractPath(closure.getContractId()) + "?tab=end"));
        }
        for (PaymentRequest p : refunds) {
            if (p.getStatus() == PaymentStatus.TRANSFER_REPORTED) {
                add.accept(task("CONFIRM_REFUND", p.getId(), p.getContractId(), p.getRentalRequestId(),
                        lookup.contract(p.getContractId()), p.getConfirmationDueAt(), clock(p), now, real, paymentPath(p)));
            }
        }
        var byLease = amendments.stream().collect(Collectors.groupingBy(ContractAmendment::getContractId));
        for (LeaseRow lease : leases) {
            LocalDate end = ends.get(lease.contractId());
            var history = byLease.getOrDefault(lease.contractId(), List.of());
            if (end != null && today.isBefore(end)) {
                for (ContractAmendment a : history) {
                    if (waitsForTenant(a, userId) && a.getRespondBy() != null && now.isBefore(a.getRespondBy())
                            && history.stream().noneMatch(next -> a.getId().equals(next.getTenantRequestId())
                            && ContractAmendmentService.PENDING.contains(next.getStatus()))) {
                        add.accept(task("AMENDMENT_RESPONSE", a.getId(), lease.contractId(), null, lease.contractNumber(),
                                a.getRespondBy(), Clock.BILLING, now, real,
                                contractPath(lease.contractId()) + "?tab=end&amendmentId=" + a.getId()));
                    }
                }
                long days = ChronoUnit.DAYS.between(today, end);
                if (days >= 1 && days <= ContractLeaseCalc.RENEWAL_WINDOW_DAYS) {
                    add.accept(task("LEASE_EXPIRING", lease.contractId(), lease.contractId(), null,
                            lease.contractNumber(), end.atStartOfDay(ZONE).toInstant(), Clock.BILLING, now, real,
                            contractPath(lease.contractId()) + "?tab=end"));
                }
            }
        }
        var numbers = leases.stream().collect(Collectors.toMap(LeaseRow::contractId, LeaseRow::contractNumber));
        for (ExitRow e : exits) {
            if (!userId.equals(e.proposedBy())) {
                add.accept(task("TERMINATION_RESPONSE", e.id(), e.contractId(), null, numbers.get(e.contractId()),
                        e.respondBy(), Clock.BILLING, now, real, contractPath(e.contractId()) + "?tab=end&focus=early-exit"));
            }
        }
        for (var proposal : reads.findTerminationProposals(userId, now, NOT_REPORTED, PageRequest.of(0, MAX_TASK_SOURCE))) {
            Instant deadline = null;
            if (proposal.history() != null && !proposal.history().isBlank()) {
                List<com.hs.contract.dto.billing.UnilateralTerminationNotice> history;
                try { history = mapper.readValue(proposal.history(), new com.fasterxml.jackson.core.type.TypeReference<>() {}); }
                catch (java.io.IOException ex) { throw new IllegalStateException("Invalid stored termination history", ex); }
                var notice = history.stream().filter(n -> proposal.invoiceId().equals(n.invoiceId()))
                        .reduce((a, b) -> b).orElse(null);
                if (notice == null || notice.status() != com.hs.contract.dto.billing.UnilateralTerminationNotice.Status.NOTICE
                        || notice.notifiedAt() == null || now.isBefore(notice.notifiedAt())) continue;
                deadline = notice.respondBy();
            }
            add.accept(task("TERMINATION_RESPONSE", "monthly:" + proposal.invoiceId(), proposal.contractId(), null,
                    proposal.contractNumber(), deadline, Clock.BILLING, now, real,
                    contractPath(proposal.contractId()) + "?tab=payment&section=monthly&invoice=" + proposal.invoiceId()));
        }
        for (SigRow s : signatures) {
            if (signing.stream().anyMatch(c -> c.id().equals(s.contractId()) && c.dueAt() != null
                    && !now.isBefore(c.dueAt()))) continue;
            add.accept(task("SIGN_CONFIRM_APP", s.contractId(), s.contractId(), null, lookup.contract(s.contractId()),
                    s.expiresAt(), Clock.REAL, now, real, contractPath(s.contractId()) + "?tab=sign"));
        }
        for (ComplaintRow c : complaints) {
            if (userId.equals(c.responseDueFor())) {
                add.accept(task("COMPLAINT_RESPOND", c.id(), c.contractId(), c.rentalRequestId(),
                        lookup.contract(c.contractId()), c.respondBy(), Clock.BILLING, now, real,
                        c.rentalRequestId() == null ? null : requestPath(c.rentalRequestId())));
            }
        }
        var all = new ArrayList<>(found.values());
        all.sort(Comparator.comparing(Task::priority).thenComparingLong(t -> remaining(t, now, real))
                .thenComparing(Task::key));
        int urgent = (int) all.stream().filter(t -> t.priority() == Priority.URGENT).count();
        return new Tasks(all.size(), urgent, List.copyOf(all.subList(0, Math.min(TASKS, all.size()))));
    }

    private Waiting waiting(String userId, Instant now, List<ContractAmendment> amendments,
                            List<LeaseRow> leases, List<com.hs.contract.model.ContractClosure> closures, Lookup lookup) {
        var rows = new ArrayList<>(reads.findWaitingRenewals(userId, now, PageRequest.of(0, MAX_TASK_SOURCE)));
        rows.addAll(reads.findWaitingExits(userId, now, PageRequest.of(0, MAX_TASK_SOURCE)));
        var numbers = leases.stream().collect(Collectors.toMap(LeaseRow::contractId, LeaseRow::contractNumber));
        for (var a : amendments) {
            boolean waitsForOwner = switch (a.getStatus()) {
                case "REQUESTED", "TENANT_REQUESTED", "TENANT_ACCEPTED" -> userId.equals(a.getProposerId());
                case "CHANGES_REQUESTED" -> userId.equals(a.getRecipientId());
                default -> false;
            };
            if (waitsForOwner && a.getRespondBy() != null && now.isBefore(a.getRespondBy())
                    && amendments.stream().noneMatch(n -> a.getId().equals(n.getTenantRequestId())
                    && ContractAmendmentService.PENDING.contains(n.getStatus()))) {
                rows.add(new WaitRow(a.getId(), "AMENDMENT_WAITING", a.getContractId(), numbers.get(a.getContractId()),
                        a.getSubmittedAt(), a.getRespondBy()));
            }
        }
        for (var c : closures) if ("DISPUTED".equals(c.getStatus()) && c.getRespondBy() != null
                && now.isBefore(c.getRespondBy())) rows.add(new WaitRow(c.getContractId(), "CLOSURE_DISPUTED",
                c.getContractId(), lookup.contract(c.getContractId()), null, c.getRespondBy()));
        rows.sort(Comparator.comparing(WaitRow::respondBy, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(WaitRow::id));
        return new Waiting(rows.size(), rows.stream().limit(10).map(w -> new WaitingItem(w.type(), w.contractId(),
                w.contractNumber(), w.sentAt(), w.respondBy(), Clock.BILLING,
                contractPath(w.contractId()) + "?tab=end")).toList());
    }

    private static Task task(String type, String id, String rentalRequestId, String label, Instant dueAt, Clock clock,
                             Instant now, Instant real, String href) {
        return task(type, id, id, rentalRequestId, label, dueAt, clock, now, real, href);
    }

    private static Task task(String type, String id, String contractId, String rentalRequestId, String label,
                             Instant dueAt, Clock clock, Instant now, Instant real, String href) {
        return new Task(type + ":" + id, type, contractId, rentalRequestId, label, dueAt, clock,
                priority(dueAt, clock == Clock.BILLING ? now : real), href);
    }

    private static long remaining(Task t, Instant now, Instant real) {
        return t.dueAt() == null ? Long.MAX_VALUE
                : Duration.between(t.clock() == Clock.BILLING ? now : real, t.dueAt()).toMillis();
    }

    /** Same bands as the landlord list: 24 hours or less is urgent, 3 days or less soon. */
    static Priority priority(Instant dueAt, Instant clockNow) {
        if (dueAt == null) return Priority.NORMAL;
        long left = Duration.between(clockNow, dueAt).toMillis();
        return left <= Duration.ofHours(24).toMillis() ? Priority.URGENT
                : left <= Duration.ofDays(3).toMillis() ? Priority.SOON : Priority.NORMAL;
    }

    private static boolean waitsForTenant(ContractAmendment a, String userId) {
        return switch (a.getStatus()) {
            case "REQUESTED", "TENANT_REQUESTED", "TENANT_ACCEPTED" -> userId.equals(a.getRecipientId());
            case "CHANGES_REQUESTED" -> userId.equals(a.getProposerId());
            default -> false;
        };
    }

    /* ---------- spending, counts ---------- */

    static Instant windowStart(YearMonth current, int months) {
        return current.minusMonths(months - 1L).atDay(1).atStartOfDay(ZONE).toInstant();
    }

    /** Months oldest first, empty months are zero; the month is the Vietnam calendar month of the receipt. */
    static Spending spending(List<SpendRow> rows, YearMonth current, int months, int pdfIssues) {
        var buckets = new LinkedHashMap<YearMonth, Map<String, BigDecimal>>();
        for (int i = months - 1; i >= 0; i--) {
            var groups = new LinkedHashMap<String, BigDecimal>();
            for (String g : List.of(DEPOSIT_FIRST, RENT, UTILITY_OTHER)) groups.put(g, BigDecimal.ZERO);
            buckets.put(current.minusMonths(i), groups);
        }
        for (SpendRow row : rows) {
            var groups = buckets.get(YearMonth.from(row.issuedAt().atZone(ZONE)));
            if (groups != null) groups.merge(group(row.type()), row.amount(), BigDecimal::add);
        }
        BigDecimal total = BigDecimal.ZERO, deposit = BigDecimal.ZERO;
        var list = new ArrayList<SpendMonth>();
        for (var e : buckets.entrySet()) {
            BigDecimal sum = e.getValue().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            total = total.add(sum);
            deposit = deposit.add(e.getValue().get(DEPOSIT_FIRST));
            list.add(new SpendMonth(e.getKey().toString(), sum, e.getValue()));
        }
        BigDecimal total12m = BigDecimal.ZERO, deposit12m = BigDecimal.ZERO;
        for (var row : rows) {
            YearMonth m = YearMonth.from(row.issuedAt().atZone(ZONE));
            if (m.isBefore(current.minusMonths(11)) || m.isAfter(current)) continue;
            total12m = total12m.add(row.amount());
            if (DEPOSIT_FIRST.equals(group(row.type()))) deposit12m = deposit12m.add(row.amount());
        }
        return new Spending("VND", total, total.subtract(deposit), total12m, total12m.subtract(deposit12m), list, pdfIssues);
    }

    static String group(PaymentType type) {
        return switch (type) {
            case INITIAL, RESERVATION_DEPOSIT, DEPOSIT_ADJUSTMENT -> DEPOSIT_FIRST;
            case MONTHLY_RENT -> RENT;
            default -> UTILITY_OTHER;
        };
    }

    /** Cancelled by anyone counts as one group; every group is always present. */
    static Map<String, Long> requestCounts(List<Object[]> rows) {
        var counts = new LinkedHashMap<String, Long>();
        for (String k : List.of("PENDING", "ACCEPTED", "REJECTED", "CANCELLED", "EXPIRED", "COMPLETED")) counts.put(k, 0L);
        for (Object[] row : rows) {
            String status = ((RentalRequestStatus) row[0]).name();
            counts.merge(status.startsWith("CANCELLED") ? "CANCELLED" : status, (Long) row[1], Long::sum);
        }
        return counts;
    }

    static Saved saved(List<Object[]> rows) {
        long total = 0, available = 0;
        for (Object[] row : rows) {
            total += (Long) row[1];
            if (row[0] == ListingStatus.PUBLISHED) available += (Long) row[1];
        }
        return new Saved(total, available, total - available);
    }

    static ExpiryStatus expiry(long days) {
        return days > 60 ? ExpiryStatus.LONG : days <= 30 ? ExpiryStatus.WITHIN_30 : ExpiryStatus.WITHIN_60;
    }

    private static String contractPath(String id) { return "/dashboard/tenant/contracts/" + id; }

    private static String requestPath(String id) {
        return id == null ? null : "/dashboard/rental-requests/my-requests?requestId=" + id;
    }

    private static String paymentPath(PaymentRequest p) {
        return p.getInvoiceId() != null && p.getContractId() != null
                ? contractPath(p.getContractId()) + "?tab=payment&section=monthly&invoice=" + p.getInvoiceId()
                : "/dashboard/payments?paymentId=" + p.getId();
    }

    /** Names, addresses, pictures, contract numbers and periods of everything above, loaded in a few batches. */
    private final class Lookup {
        private final Map<String, ListingInfo> listings;
        private final Map<String, String> thumbnails = new HashMap<>();
        private final Map<String, String> landlords;
        private final Map<String, String> contracts = new HashMap<>();
        private final Map<String, Integer> periods;

        Lookup(String userId, List<LeaseRow> leases, List<PaymentRequest> open, List<PaymentRequest> refunds,
               List<SignRow> signing, List<SigRow> signatures, List<ComplaintRow> complaints, List<HoldRow> holds,
               List<VisitRow> visits, List<RequestRow> requests, List<com.hs.contract.model.ContractClosure> closures) {
            var listingIds = new HashSet<String>();
            leases.forEach(l -> listingIds.add(l.listingId()));
            open.forEach(p -> listingIds.add(p.getListingId()));
            holds.forEach(h -> listingIds.add(h.listingId()));
            visits.forEach(v -> listingIds.add(v.listingId()));
            requests.forEach(r -> listingIds.add(r.listingId()));
            listingIds.remove(null);
            listings = listingIds.isEmpty() ? Map.of() : reads.findListingInfo(listingIds).stream()
                    .collect(Collectors.toMap(ListingInfo::id, Function.identity(), (a, b) -> a));
            if (!listingIds.isEmpty()) {
                var best = new HashMap<String, Media>();
                for (Media m : reads.findPublicImages(listingIds, MediaType.IMAGE, StorageVisibility.PUBLIC)) {
                    best.merge(m.listingId(), m, (a, b) -> better(a, b) ? a : b);
                }
                best.forEach((id, m) -> thumbnails.put(id, "https://%s.s3.%s.amazonaws.com/%s"
                        .formatted(m.bucket(), storage.region(), m.key())));
            }
            var landlordIds = leases.stream().map(LeaseRow::landlordId).collect(Collectors.toSet());
            landlords = landlordIds.isEmpty() ? Map.of() : reads.findNames(landlordIds).stream().collect(
                    Collectors.toMap(Name::id, n -> (Objects.toString(n.firstName(), "") + " "
                            + Objects.toString(n.lastName(), "")).trim(), (a, b) -> a));
            leases.forEach(l -> contracts.put(l.contractId(), l.contractNumber()));
            var contractIds = new HashSet<String>();
            open.forEach(p -> contractIds.add(p.getContractId()));
            refunds.forEach(p -> contractIds.add(p.getContractId()));
            signatures.forEach(s -> contractIds.add(s.contractId()));
            complaints.forEach(c -> contractIds.add(c.contractId()));
            closures.forEach(c -> contractIds.add(c.getContractId()));
            contractIds.remove(null);
            contractIds.removeAll(contracts.keySet());
            if (!contractIds.isEmpty()) reads.findOwnContractNumbers(contractIds, userId)
                    .forEach(r -> contracts.put(r.id(), r.value()));
            var invoiceIds = open.stream().map(PaymentRequest::getInvoiceId).filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            periods = invoiceIds.isEmpty() ? Map.of() : reads.findInvoicePeriods(invoiceIds, userId).stream()
                    .collect(Collectors.toMap(InvoicePeriod::id, InvoicePeriod::periodIndex, (a, b) -> a));
        }

        private static boolean better(Media a, Media b) {
            if (a.cover() != b.cover()) return a.cover();
            return Objects.requireNonNullElse(a.sortOrder(), Integer.MAX_VALUE)
                    <= Objects.requireNonNullElse(b.sortOrder(), Integer.MAX_VALUE);
        }

        String title(String id) { var l = id == null ? null : listings.get(id); return l == null ? null : l.title(); }
        String address(String id) { var l = id == null ? null : listings.get(id); return l == null ? null : l.address(); }
        String thumbnail(String id) { return id == null ? null : thumbnails.get(id); }
        String landlord(String id) { return landlords.get(id); }
        String contract(String id) { return id == null ? null : contracts.get(id); }
        Integer period(String id) { return id == null ? null : periods.get(id); }
    }
}
