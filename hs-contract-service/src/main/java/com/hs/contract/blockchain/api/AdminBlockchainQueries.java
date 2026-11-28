package com.hs.contract.blockchain.api;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.*;
import java.time.Instant;
import java.util.*;

import static com.hs.contract.blockchain.api.AdminBlockchainResponse.*;

@Repository
@RequiredArgsConstructor
public class AdminBlockchainQueries {
    private final JdbcTemplate jdbc;
    private static final Set<String> ERRORS = Set.of("DOCUMENT_HASH_CONFLICT", "DOCUMENT_LINEAGE_CONFLICT",
            "SOURCE_PDF_CHANGED", "TRANSACTION_MISMATCH", "TRANSACTION_REVERTED", "RPC_UNAVAILABLE", "RPC_REJECTED",
            "INVALID_NETWORK_CONFIG", "WRONG_CHAIN", "REGISTRY_NOT_FOUND", "OWNER_MISMATCH", "RPC_NOT_CONFIGURED",
            "PRIVATE_KEY_NOT_CONFIGURED", "PRIVATE_KEY_INVALID", "INVALID_BYTES32", "INVALID_RECEIPT",
            "INCOMPATIBLE_REGISTRY", "UNEXPECTED_GAS", "INSUFFICIENT_GAS", "FINALITY_UNAVAILABLE",
            "TRANSACTION_REVERT_PENDING_FINALITY", "REGISTRATION_NOT_LOCATED", "PROCESSING_UNAVAILABLE");
    private static final String FROM = " from contract_document_proofs p join contracts c on c.id=p.contract_id";
    private static final String COLUMNS = "p.document_id,c.contract_number,p.document_kind,p.status,p.transaction_hash,"
            + "p.block_number,p.created_at,p.confirmed_at,p.error_code";
    public record Queue(Map<String, Long> counts, long stuck, Instant oldest, long retrying, Map<String, Long> errors) {}

    public Queue queue(Instant now) {
        var counts = new LinkedHashMap<String, Long>();
        for (String s : List.of("PENDING", "SUBMITTED", "CONFIRMED", "FAILED")) counts.put(s, 0L);
        jdbc.query("select status,count(*) n from contract_document_proofs group by status", rs -> {
            counts.merge(counts.containsKey(rs.getString(1)) ? rs.getString(1) : "OTHER", rs.getLong(2), Long::sum);
        });
        var totals = jdbc.queryForMap("""
                select count(*) filter (where created_at < ?) stuck, min(created_at) oldest,
                  count(*) filter (where attempts>0) retrying from contract_document_proofs
                where status in ('PENDING','SUBMITTED')
                """, Timestamp.from(now.minusSeconds(900)));
        var errors = new TreeMap<String, Long>();
        String known = String.join(",", Collections.nCopies(ERRORS.size(), "?"));
        jdbc.query("select case when error_code in (" + known + ") then error_code else 'OTHER' end safe_code,count(*) "
                        + "from contract_document_proofs where error_code is not null group by safe_code",
                rs -> { errors.put(rs.getString(1), rs.getLong(2)); }, ERRORS.toArray());
        var oldest = (Timestamp) totals.get("oldest");
        return new Queue(counts, ((Number) totals.get("stuck")).longValue(), oldest == null ? null : oldest.toInstant(),
                ((Number) totals.get("retrying")).longValue(), errors);
    }

    public Map<String, Long> integrityCounts() {
        var result = new TreeMap<String, Long>();
        for (String s : List.of("MATCH", "MISMATCH", "MISSING", "UNAVAILABLE", "PENDING", "NOT_AVAILABLE", "UNCHECKED")) result.put(s, 0L);
        jdbc.query("select case when integrity_status is null then 'UNCHECKED' "
                + "when integrity_status in ('MATCH','MISMATCH','MISSING','UNAVAILABLE','PENDING','NOT_AVAILABLE') "
                + "then integrity_status else 'OTHER' end safe_status,count(*) from contract_documents group by safe_status", rs -> {
            String status = rs.getString(1);
            result.merge(status == null ? "UNCHECKED" : result.containsKey(status) ? status : "OTHER", rs.getLong(2), Long::sum);
        });
        return result;
    }
    public long openIncidents() {
        return jdbc.queryForObject("select count(*) from operational_incidents where component='BLOCKCHAIN' and open=true", Long.class);
    }
    public Instant senderLease() {
        return jdbc.query("select expires_at from blockchain_sender_leases where id='contract-document-proof-sender'",
                rs -> rs.next() ? instant(rs, "expires_at") : null);
    }
    public Page<ProofRow> proofs(String status, String prefix, Instant from, Instant to, Pageable page) {
        StringBuilder where = new StringBuilder(" where 1=1");
        var args = new ArrayList<Object>();
        if (status != null) { where.append(" and p.status=?"); args.add(status); }
        if (prefix != null) { where.append(" and c.contract_number like ? escape '!'"); args.add(prefix.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%"); }
        if (from != null) { where.append(" and p.created_at>=?"); args.add(Timestamp.from(from)); }
        if (to != null) { where.append(" and p.created_at<?"); args.add(Timestamp.from(to)); }
        long total = jdbc.queryForObject("select count(*)" + FROM + where, Long.class, args.toArray());
        args.add(page.getPageSize()); args.add(page.getOffset());
        var rows = jdbc.query("select " + COLUMNS + FROM + where + " order by p.created_at desc,p.document_id desc limit ? offset ?",
                (rs,n) -> new ProofRow(rs.getString("document_id"), rs.getString("contract_number"),
                        rs.getObject("document_kind", Integer.class), rs.getString("status"), hash(rs.getString("transaction_hash")),
                        rs.getObject("block_number", Long.class), instant(rs,"created_at"), instant(rs,"confirmed_at"), safeError(rs.getString("error_code"))), args.toArray());
        return new PageImpl<>(rows, page, total);
    }
    public Optional<ProofDetail> proof(String id) {
        return jdbc.query("select " + COLUMNS + ",p.document_hash,p.on_chain_document_id,p.chain_id,p.registry_address,"
                        + "p.block_hash,p.registered_at,p.case_id,p.attempts,p.submitted_at" + FROM + " where p.document_id=?",
                (rs,n) -> new ProofDetail(rs.getString("document_id"), rs.getString("contract_number"), rs.getObject("document_kind",Integer.class),
                        rs.getString("status"), hash(rs.getString("document_hash")), hash(rs.getString("on_chain_document_id")), rs.getLong("chain_id"),
                        address(rs.getString("registry_address")), hash(rs.getString("transaction_hash")), rs.getObject("block_number",Long.class),
                        hash(rs.getString("block_hash")), instant(rs,"registered_at"), instant(rs,"created_at"), instant(rs,"confirmed_at"),
                        safeError(rs.getString("error_code")), hash(rs.getString("case_id")), rs.getInt("attempts"), instant(rs,"submitted_at")), id).stream().findFirst();
    }
    private static Instant instant(ResultSet rs, String name) throws SQLException {
        var t = rs.getTimestamp(name); return t == null ? null : t.toInstant();
    }
    public static String safeError(String code) { return code == null ? null : ERRORS.contains(code) ? code : "OTHER"; }
    public static String hash(String value) { return value != null && value.matches("0x[0-9a-fA-F]{64}") ? value : null; }
    public static String address(String value) { return value != null && value.matches("0x[0-9a-fA-F]{40}") ? value : null; }
}
