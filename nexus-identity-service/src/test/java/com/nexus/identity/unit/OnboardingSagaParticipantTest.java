package com.nexus.identity.unit;

import com.nexus.identity.application.command.UserCommandService;
import com.nexus.identity.application.saga.OnboardingSagaParticipant;
import com.nexus.identity.domain.command.CancelRegistrationCommand;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OnboardingSagaParticipantTest {

    @Mock private UserCommandService userCommandService;

    private OnboardingSagaParticipant participant;

    @BeforeEach
    void setUp() {
        participant = new OnboardingSagaParticipant(userCommandService, ObservationRegistry.NOOP);
    }

    @Test
    void compensateDelegatesToCancelRegistration() {
        CancelRegistrationCommand command = new CancelRegistrationCommand(
                UUID.randomUUID(), "saga-1", "account-creation-failed", "trace-1");

        participant.compensate(command);

        verify(userCommandService).cancelRegistration(command.userId(), command.sagaId(), command.traceId());
    }

    @Test
    void compensatePropagatesExceptionForKafkaRedelivery() {
        CancelRegistrationCommand command = new CancelRegistrationCommand(
                UUID.randomUUID(), "saga-1", "reason", "trace-1");
        doThrow(new RuntimeException("db down"))
                .when(userCommandService).cancelRegistration(any(), any(), any());

        assertThatThrownBy(() -> participant.compensate(command))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("db down");
    }

    @Test
    void canCompensateReturnsTrueByDefault() {
        assertThat(participant.canCompensate(UUID.randomUUID())).isTrue();
    }
}
