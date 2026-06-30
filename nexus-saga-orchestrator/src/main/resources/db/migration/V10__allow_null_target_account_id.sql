-- Deposits (DIRECT_DEPOSIT, CASH_IN) have no target account — allow NULL.
ALTER TABLE public.transfer_sagas ALTER COLUMN target_account_id DROP NOT NULL;
