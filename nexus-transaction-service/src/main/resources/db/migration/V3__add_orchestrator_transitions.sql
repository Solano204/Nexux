-- Add state transitions required for the saga orchestrator flow.
-- Orchestrator reserves balance before fraud check (reverse of choreography).
-- COMPLETING is an in-memory-only state; Hibernate merges LEDGER_POSTED → COMPLETED.

CREATE OR REPLACE FUNCTION validate_transaction_state_transition()
RETURNS TRIGGER AS $$
DECLARE
    valid_transitions TEXT[][] := ARRAY[
        -- Choreography flow: fraud check first
        ARRAY['INITIATED', 'FRAUD_CHECKING'],
        ARRAY['INITIATED', 'CANCELLED'],
        ARRAY['FRAUD_CHECKING', 'FRAUD_CLEARED'],
        ARRAY['FRAUD_CHECKING', 'FRAUD_REJECTED'],
        ARRAY['FRAUD_CLEARED', 'BALANCE_RESERVING'],
        -- Orchestrator flow: balance reservation first
        ARRAY['INITIATED', 'BALANCE_RESERVING'],
        ARRAY['BALANCE_RESERVING', 'BALANCE_RESERVED'],
        ARRAY['BALANCE_RESERVING', 'RESERVE_FAILED'],
        -- Orchestrator flow: fraud rejected after balance reserved
        ARRAY['BALANCE_RESERVED', 'FRAUD_REJECTED'],
        -- Ledger posting
        ARRAY['BALANCE_RESERVED', 'LEDGER_POSTING'],
        ARRAY['FRAUD_CLEARED', 'LEDGER_POSTING'],
        ARRAY['LEDGER_POSTING', 'LEDGER_POSTED'],
        ARRAY['LEDGER_POSTING', 'LEDGER_FAILED'],
        ARRAY['LEDGER_POSTED', 'COMPLETING'],
        ARRAY['COMPLETING', 'COMPLETED'],
        -- COMPLETING is in-memory only; Hibernate may write LEDGER_POSTED → COMPLETED directly
        ARRAY['LEDGER_POSTED', 'COMPLETED'],
        -- Failure paths
        ARRAY['RESERVE_FAILED', 'FAILED'],
        ARRAY['LEDGER_FAILED', 'REVERSING'],
        ARRAY['REVERSING', 'REVERSED'],
        ARRAY['FRAUD_REJECTED', 'FAILED']
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
