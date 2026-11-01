-- Hibernate ddl-auto=update does not refresh CHECK constraints after PaymentType grows.
BEGIN;
ALTER TABLE payment_requests DROP CONSTRAINT IF EXISTS payment_requests_type_check;
ALTER TABLE payment_requests ADD CONSTRAINT payment_requests_type_check CHECK (
    type IN (
        'INITIAL',
        'RESERVATION_DEPOSIT',
        'MONTHLY_RENT',
        'UTILITY',
        'OTHER_CHARGE',
        'DEPOSIT_REFUND',
        'DEPOSIT_ADJUSTMENT',
        'INITIAL_PAYMENT_REFUND'
    )
);
ALTER TABLE payment_events DROP CONSTRAINT IF EXISTS payment_events_event_type_check;
ALTER TABLE payment_events ADD CONSTRAINT payment_events_event_type_check CHECK (
    event_type IN (
        'CREATED',
        'TRANSFER_REPORTED',
        'RECEIPT_CONFIRMED',
        'RECEIPT_REJECTED',
        'DISPUTED',
        'EXPIRED',
        'CANCELLED',
        'REFUND_OBLIGATION_CREATED',
        'OVERDUE',
        'AMOUNT_ADJUSTED',
        'DUE_DATE_EXTENDED'
    )
);
COMMIT;
