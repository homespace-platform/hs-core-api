-- Chạy thủ công trước khi khởi động Core nếu schema dùng validate/none.
-- Dev dùng ddl-auto=update có thể để Hibernate tạo hai bảng này.
BEGIN;

CREATE TABLE IF NOT EXISTS contract_document_proofs (
    document_id varchar(36) PRIMARY KEY,
    contract_id varchar(36) NOT NULL,
    revision_id varchar(36) NOT NULL,
    storage_object_id varchar(36) NOT NULL,
    on_chain_document_id varchar(66) NOT NULL,
    document_hash varchar(66) NOT NULL,
    chain_id bigint NOT NULL,
    registry_address varchar(42) NOT NULL,
    status varchar(20) NOT NULL CHECK (status IN ('PENDING', 'SUBMITTED', 'CONFIRMED', 'FAILED')),
    signer_address varchar(42),
    nonce varchar(78),
    signed_raw_transaction text,
    transaction_hash varchar(66),
    block_number bigint,
    block_hash varchar(66),
    registered_at timestamp(6) with time zone,
    submitted_at timestamp(6) with time zone,
    confirmed_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    next_attempt_at timestamp(6) with time zone NOT NULL,
    attempts integer NOT NULL,
    error_code varchar(64),
    error_message varchar(500),
    version bigint,
    CONSTRAINT uq_document_proof_chain_id UNIQUE (chain_id, registry_address, on_chain_document_id),
    CONSTRAINT uq_document_proof_nonce UNIQUE (chain_id, signer_address, nonce)
);
CREATE INDEX IF NOT EXISTS idx_document_proof_due ON contract_document_proofs(status, next_attempt_at, created_at);
CREATE INDEX IF NOT EXISTS idx_document_proof_contract ON contract_document_proofs(contract_id);

CREATE TABLE IF NOT EXISTS blockchain_sender_leases (
    id varchar(128) PRIMARY KEY,
    owner_token varchar(36) NOT NULL,
    expires_at timestamp(6) with time zone NOT NULL
);

COMMIT;
