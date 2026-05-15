package com.nexus.assistant.application;

import com.nexus.assistant.agent.FinancialAssistantAgent;
import com.nexus.assistant.infrastructure.kafka.AiQueryEventProducer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final FinancialAssistantAgent agent;
    private final AiQueryEventProducer eventProducer;
    private final ObservationRegistry observationRegistry;

    public Flux<String> chat(String message, String userId,
                             String sessionId) {

        String conversationId = userId + ":" + sessionId;
        Instant start = Instant.now();

        Observation obs = Observation.createNotStarted(
                        "ai.chat.process", observationRegistry)
                .start();

        return agent.chat(message, conversationId, userId)
                .doOnComplete(() -> {
                    obs.event(Observation.Event.of("chat.complete"));
                    obs.stop();
                    // Publish analytics event
                    eventProducer.publishQueryLogged(
                            userId, sessionId, message,
                            Instant.now().toEpochMilli() -
                                    start.toEpochMilli());
                    log.info("Chat complete: userId={} sessionId={}",
                            userId, sessionId);
                })
                .doOnError(e -> {
                    obs.error(e);
                    obs.stop();
                });
    }
}