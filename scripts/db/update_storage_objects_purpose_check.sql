-- Migration: Update storage_objects_purpose_check constraint to include PAYMENT_PROOF
-- Idempotent script for PostgreSQL

DO $$
BEGIN
    -- 1. Drop existing constraint if it exists
    IF EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'storage_objects_purpose_check'
    ) THEN
        ALTER TABLE storage_objects DROP CONSTRAINT storage_objects_purpose_check;
    END IF;

    -- 2. Add updated constraint with all current StoragePurpose enum values
    ALTER TABLE storage_objects
    ADD CONSTRAINT storage_objects_purpose_check
    CHECK (
        purpose IN (
            'USER_AVATAR',
            'CONTRACT_DOCUMENT',
            'IDENTITY_DOCUMENT',
            'CHAT_ATTACHMENT',
            'LISTING_IMAGE',
            'LISTING_VIDEO',
            'NEWS_IMAGE',
            'PAYMENT_PROOF',
            'GENERAL'
        )
    );
END $$;
