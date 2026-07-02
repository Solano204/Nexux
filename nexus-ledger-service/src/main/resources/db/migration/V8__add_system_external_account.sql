-- System external funds account: debit side for DIRECT_DEPOSIT / CASH_IN entries.
-- Fixed UUID so LedgerCommandConsumer can reference it without a lookup.
INSERT INTO chart_of_accounts
    (account_id, account_number, account_name,
     account_type, account_subtype, normal_balance,
     is_user_account, opening_balance)
VALUES
    ('00000000-0000-0000-0000-000000000001', 'SYS-EXTERNAL-FUNDS',
     'External Funds', 'ASSET', 'EXTERNAL', 'DEBIT',
     FALSE, 999999999999.0000)
ON CONFLICT (account_id) DO NOTHING;
