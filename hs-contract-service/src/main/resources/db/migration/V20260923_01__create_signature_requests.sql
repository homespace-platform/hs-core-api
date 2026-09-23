-- Manual PostgreSQL migration for deployments using JPA_DDL_AUTO=validate.
-- The project does not currently run Flyway automatically; apply through deployment migrations.
CREATE TABLE IF NOT EXISTS signature_requests (
    id VARCHAR(36) PRIMARY KEY,
    contract_id VARCHAR(36) NOT NULL,
    revision_id VARCHAR(36) NOT NULL,
    source_document_id VARCHAR(36) NOT NULL,
    signed_document_id VARCHAR(36),
    signer_user_id VARCHAR(36) NOT NULL,
    signer_role VARCHAR(20) NOT NULL,
    status VARCHAR(30) NOT NULL,
    doc_id VARCHAR(100) NOT NULL,
    provider_transaction_id VARCHAR(100),
    provider_tran_code VARCHAR(100),
    certificate_serial VARCHAR(128),
    certificate_subject TEXT,
    signer_cccd VARCHAR(20),
    prepared_document_storage_id VARCHAR(36),
    prepared_byte_range VARCHAR(100),
    prepared_placeholder_offset BIGINT,
    prepared_placeholder_length INTEGER,
    prepared_sign_date TIMESTAMP WITH TIME ZONE,
    provider_signature_value TEXT,
    provider_cert_data TEXT,
    provider_chain_data TEXT,
    initiated_at TIMESTAMP WITH TIME ZONE,
    confirmed_at TIMESTAMP WITH TIME ZONE,
    expires_at TIMESTAMP WITH TIME ZONE,
    failed_at TIMESTAMP WITH TIME ZONE,
    failure_code VARCHAR(50),
    failure_message VARCHAR(500),
    attempt_number INTEGER NOT NULL DEFAULT 1,
    processing_started_at TIMESTAMP WITH TIME ZONE,
    processing_attempts INTEGER NOT NULL DEFAULT 0,
    last_provider_checked_at TIMESTAMP WITH TIME ZONE,
    version BIGINT,
    created_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE,
    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE INDEX IF NOT EXISTS idx_sig_req_contract ON signature_requests(contract_id, signer_role);
CREATE INDEX IF NOT EXISTS idx_sig_req_status ON signature_requests(status);
CREATE INDEX IF NOT EXISTS idx_sig_req_doc_id ON signature_requests(doc_id);
CREATE INDEX IF NOT EXISTS idx_sig_req_tran_code ON signature_requests(provider_tran_code);
CREATE UNIQUE INDEX IF NOT EXISTS uk_sig_req_provider_transaction
    ON signature_requests(provider_transaction_id) WHERE provider_transaction_id IS NOT NULL;
