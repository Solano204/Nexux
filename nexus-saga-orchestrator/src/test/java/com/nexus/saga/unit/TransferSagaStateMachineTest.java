package com.nexus.saga.unit;

import com.nexus.saga.domain.model.transfer.TransferStep;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure state-machine tests — no mocks, no Spring context. Exhaustively
 * checks TransferStep.canTransitionTo() against the diagram from
 * Fase 4/2 so a future edit to the enum can't silently create an
 * unreachable step or an illegal transition without a test failing.
 */
class TransferSagaStateMachineTest {

    @Test
    void happyPath_isFullyConnected() {
        assertThat(TransferStep.STARTED.canTransitionTo(TransferStep.BALANCE_RESERVING)).isTrue();
        // No separately-persisted BALANCE_RESERVED step — see TransferStep's
        // class doc. Reserving funds and starting the fraud check happen as
        // one saga-orchestrator decision.
        assertThat(TransferStep.BALANCE_RESERVING.canTransitionTo(TransferStep.FRAUD_CHECKING)).isTrue();
        assertThat(TransferStep.FRAUD_CHECKING.canTransitionTo(TransferStep.FRAUD_CLEARED)).isTrue();
        assertThat(TransferStep.FRAUD_CLEARED.canTransitionTo(TransferStep.LEDGER_POSTING)).isTrue();
        assertThat(TransferStep.LEDGER_POSTING.canTransitionTo(TransferStep.LEDGER_POSTED)).isTrue();
        assertThat(TransferStep.LEDGER_POSTED.canTransitionTo(TransferStep.BALANCE_FINALIZING)).isTrue();
        assertThat(TransferStep.BALANCE_FINALIZING.canTransitionTo(TransferStep.BALANCE_FINALIZED)).isTrue();
        assertThat(TransferStep.BALANCE_FINALIZED.canTransitionTo(TransferStep.NOTIFICATION_SENDING)).isTrue();
        assertThat(TransferStep.NOTIFICATION_SENDING.canTransitionTo(TransferStep.COMPLETED)).isTrue();
    }

    @Test
    void prePivotFailures_allRouteToReleasingBalance() {
        assertThat(TransferStep.FRAUD_REJECTED.canTransitionTo(TransferStep.RELEASING_BALANCE)).isTrue();
        assertThat(TransferStep.LEDGER_FAILED.canTransitionTo(TransferStep.RELEASING_BALANCE)).isTrue();
        assertThat(TransferStep.TIMED_OUT.canTransitionTo(TransferStep.RELEASING_BALANCE)).isTrue();
    }

    @Test
    void postPivotSteps_neverTransitionToReleasingBalance() {
        // The Fase 2 fix: BALANCE_FINALIZING and NOTIFICATION_SENDING must
        // never route through the pre-pivot compensation path, because the
        // ledger has already posted by the time either is reached.
        assertThat(TransferStep.BALANCE_FINALIZING.canTransitionTo(TransferStep.RELEASING_BALANCE)).isFalse();
        assertThat(TransferStep.NOTIFICATION_SENDING.canTransitionTo(TransferStep.RELEASING_BALANCE)).isFalse();
    }

    @Test
    void postPivotSteps_supportRetrySelfLoopAndEscalation() {
        assertThat(TransferStep.BALANCE_FINALIZING.canTransitionTo(TransferStep.BALANCE_FINALIZING)).isTrue();
        assertThat(TransferStep.BALANCE_FINALIZING.canTransitionTo(TransferStep.PERMANENTLY_FAILED)).isTrue();
        assertThat(TransferStep.RELEASING_BALANCE.canTransitionTo(TransferStep.RELEASING_BALANCE)).isTrue();
        assertThat(TransferStep.RELEASING_BALANCE.canTransitionTo(TransferStep.PERMANENTLY_FAILED)).isTrue();
    }

    @Test
    void notificationSending_onlyEverCompletes() {
        // Regression guard for the SagaTimeoutMonitor bug: NOTIFICATION_SENDING
        // must never be able to reach TIMED_OUT/RELEASING_BALANCE again.
        assertThat(TransferStep.NOTIFICATION_SENDING.canTransitionTo(TransferStep.COMPLETED)).isTrue();
        assertThat(TransferStep.NOTIFICATION_SENDING.canTransitionTo(TransferStep.TIMED_OUT)).isFalse();
        assertThat(TransferStep.NOTIFICATION_SENDING.canTransitionTo(TransferStep.RELEASING_BALANCE)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = TransferStep.class, names = {"COMPLETED", "COMPENSATION_COMPLETED", "PERMANENTLY_FAILED"})
    void terminalSteps_haveNoOutgoingTransitions(TransferStep terminal) {
        for (TransferStep candidate : TransferStep.values()) {
            assertThat(terminal.canTransitionTo(candidate))
                    .as("%s -> %s should be illegal (terminal step)", terminal, candidate)
                    .isFalse();
        }
    }

    @ParameterizedTest
    @EnumSource(TransferStep.class)
    void isTerminal_matchesExactlyTheThreeTerminalSteps(TransferStep step) {
        boolean expectedTerminal = step == TransferStep.COMPLETED
                || step == TransferStep.COMPENSATION_COMPLETED
                || step == TransferStep.PERMANENTLY_FAILED;
        assertThat(step.isTerminal()).isEqualTo(expectedTerminal);
    }
}
