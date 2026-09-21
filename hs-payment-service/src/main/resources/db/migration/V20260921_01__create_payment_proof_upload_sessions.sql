-- ===============================================================
-- Database Migration: Payment Proof Upload Sessions (Mobile Handoff)
-- ===============================================================

CREATE TABLE IF NOT EXISTS payment_proof_upload_sessions (
    id VARCHAR(36) PRIMARY KEY,
    payment_request_id VARCHAR(36) NOT NULL,
    tenant_user_id VARCHAR(36) NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    storage_id VARCHAR(36),
    original_file_name VARCHAR(255),
    content_type VARCHAR(100),
    file_size BIGINT,
    status VARCHAR(30) NOT NULL DEFAULT 'CREATED',
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    uploaded_at TIMESTAMP WITH TIME ZONE,
    consumed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE,
    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE INDEX IF NOT EXISTS idx_proof_session_request ON payment_proof_upload_sessions(payment_request_id);
CREATE INDEX IF NOT EXISTS idx_proof_session_tenant ON payment_proof_upload_sessions(tenant_user_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_proof_session_token_hash ON payment_proof_upload_sessions(token_hash);
