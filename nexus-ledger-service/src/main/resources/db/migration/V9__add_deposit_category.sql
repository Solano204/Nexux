-- Add DEPOSIT category for DIRECT_DEPOSIT and CASH_IN posting types.
ALTER TABLE public.ledger_entries DROP CONSTRAINT ledger_entries_category_check;

ALTER TABLE public.ledger_entries
    ADD CONSTRAINT ledger_entries_category_check
    CHECK (category IN (
        'TRANSFER', 'PAYMENT', 'FEE',
        'INTEREST', 'REFUND', 'REVERSAL',
        'INITIAL_BALANCE', 'REGULATORY_HOLD',
        'FRAUD_RESERVE', 'ADJUSTMENT', 'DEPOSIT'));
