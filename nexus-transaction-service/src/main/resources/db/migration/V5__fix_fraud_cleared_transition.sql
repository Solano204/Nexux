-- Balance-first saga: fraud check happens AFTER balance reservation.
-- Add BALANCE_RESERVED → FRAUD_CLEARED as a valid transition.

CREATE OR REPLACE FUNCTION validate_transaction_state_transition()
RETURNS TRIGGER AS $$
DECLARE
    valid_transitions TEXT[][] := ARRAY[
        ARRAY['INITIATED',        'BALANCE_RESERVING'],
        ARRAY['INITIATED',        'CANCELLED'],
        ARRAY['BALANCE_RESERVING','BALANCE_RESERVED'],
        ARRAY['BALANCE_RESERVING','RESERVE_FAILED'],
        -- fraud check after balance reservation
        ARRAY['BALANCE_RESERVED', 'FRAUD_CLEARED'],
        ARRAY['BALANCE_RESERVED', 'FRAUD_REJECTED'],
        ARRAY['BALANCE_RESERVED', 'LEDGER_POSTING'],
        -- legacy fraud-first flow kept for existing records
        ARRAY['INITIATED',        'FRAUD_CHECKING'],
        ARRAY['FRAUD_CHECKING',   'FRAUD_CLEARED'],
        ARRAY['FRAUD_CHECKING',   'FRAUD_REJECTED'],
        ARRAY['FRAUD_CLEARED',    'BALANCE_RESERVING'],
        ARRAY['FRAUD_CLEARED',    'LEDGER_POSTING'],
        -- ledger
        ARRAY['LEDGER_POSTING',   'LEDGER_POSTED'],
        ARRAY['LEDGER_POSTING',   'LEDGER_FAILED'],
        ARRAY['LEDGER_POSTED',    'COMPLETING'],
        ARRAY['LEDGER_POSTED',    'COMPLETED'],
        ARRAY['COMPLETING',       'COMPLETED'],
        -- failure paths
        ARRAY['RESERVE_FAILED',   'FAILED'],
        ARRAY['LEDGER_FAILED',    'REVERSING'],
        ARRAY['REVERSING',        'REVERSED'],
        ARRAY['FRAUD_REJECTED',   'FAILED']
    ];
    transition TEXT[];
    is_valid BOOLEAN := FALSE;
BEGIN
    IF OLD.status = NEW.status THEN
        RETURN NEW;
    END IF;

    FOREACH transition SLICE 1 IN ARRAY valid_transitions LOOP
        IF transition[1] = OLD.status
                AND transition[2] = NEW.status THEN
            is_valid := TRUE;
            EXIT;
        END IF;
    END LOOP;

    IF NOT is_valid THEN
        RAISE EXCEPTION
            'Invalid transaction state transition: % → % for txn %',
            OLD.status, NEW.status, OLD.transaction_id;
    END IF;

    NEW.updated_at := NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
