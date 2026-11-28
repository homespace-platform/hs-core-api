package com.hs.payment.membership;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

/** Select-only reconciliation view; wallet, signature and owner fields never leave the payment module. */
@Repository
@RequiredArgsConstructor
public class AdminEthPaymentQueries {
    private final JdbcTemplate jdbc;
    private static final String FROM = " from membership_eth_quotes q join membership_orders o on o.id=q.membership_order_id"
            + " where q.transaction_hash is not null and o.provider='ETH'";
    public record Payment(String orderId, String tier, String kind, BigDecimal amountVnd, String amountWei,
                          String transactionHash, Instant confirmedAt, String status, Instant orderCreatedAt,
                          long ageSeconds, long chainId, String chainSource) {}
    public record Counts(long total, long paid, long unsettled) {}

    public Counts counts() {
        return jdbc.queryForObject("select count(*) total, count(*) filter (where o.status='PAID') paid,"
                + " count(*) filter (where o.status<>'PAID') unsettled" + FROM,
                (rs, n) -> new Counts(rs.getLong("total"), rs.getLong("paid"), rs.getLong("unsettled")));
    }

    public long unsettledCount() {
        return jdbc.queryForObject("select count(*)" + FROM + " and o.status <> 'PAID'", Long.class);
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Page<Payment> payments(boolean unsettled, Pageable page, Instant now) {
        String filter = FROM + (unsettled ? " and o.status <> 'PAID'" : "");
        long total = jdbc.queryForObject("select count(*)" + filter, Long.class);
        var rows = jdbc.query("select o.id,o.tier,o.kind,q.amount_vnd,q.amount_wei,q.transaction_hash,q.confirmed_at,"
                + "o.status,o.created_at" + filter + " order by o.created_at desc,q.payment_id desc limit ? offset ?",
                (rs, n) -> {
                    var confirmed = rs.getTimestamp("confirmed_at");
                    Instant created = rs.getTimestamp("created_at").toInstant();
                    Instant at = confirmed == null ? null : confirmed.toInstant();
                    String hash = rs.getString("transaction_hash");
                    return new Payment(rs.getString("id"), rs.getString("tier"), rs.getString("kind"),
                            rs.getBigDecimal("amount_vnd"), rs.getString("amount_wei"),
                            hash.matches("0x[0-9a-fA-F]{64}") ? hash : null, at, rs.getString("status"), created,
                            Math.max(0, java.time.Duration.between(at == null ? created : at, now).getSeconds()),
                            11155111L, "ASSUMED_SEPOLIA_NO_STORED_CHAIN_ID");
                }, page.getPageSize(), page.getOffset());
        return new PageImpl<>(rows, page, total);
    }
}
