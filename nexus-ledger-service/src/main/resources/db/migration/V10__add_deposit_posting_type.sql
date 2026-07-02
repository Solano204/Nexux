-- Add DIRECT_DEPOSIT and CASH_IN to postings posting_type constraint.
ALTER TABLE public.postings DROP CONSTRAINT postings_posting_type_check;

ALTER TABLE public.postings
    ADD CONSTRAINT postings_posting_type_check
    CHECK (posting_type IN (
        'TRANSFER', 'PAYMENT', 'FEE',
        'INTEREST_PAYMENT', 'INTEREST_ACCRUAL',
        'REVERSAL', 'INITIAL_BALANCE',
        'ADJUSTMENT', 'REGULATORY_HOLD',
        'DIRECT_DEPOSIT', 'CASH_IN'));
