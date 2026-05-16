package com.nexus.payment.lambda.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.payment.lambda.model.NormalizedPaymentEvent;
import com.nexus.payment.lambda.model.enums.FailureReason;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit; /**
 * MSK bridge — publishes directly to Amazon MSK Kafka cluster.
 * Used in production when Lambda and MSK share a VPC.
 */
public class MskKafkaBridgeClient extends KafkaBridgeClient {

    private static final Logger log =
            LoggerFactory.getLogger(MskKafkaBridgeClient.class);

    private final KafkaProducer<String, String> producer;

    MskKafkaBridgeClient(String bootstrapServers,
                         ObjectMapper mapper) {
        super(mapper);

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class.getName());

        // Durability: acks=all + idempotent producer
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);

        // Lambda-appropriate timeouts
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10000);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 15000);
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5000);

        // MSK IAM authentication
        props.put("security.protocol", "SASL_SSL");
        props.put("sasl.mechanism", "AWS_MSK_IAM");
        props.put("sasl.jaas.config",
                "software.amazon.msk.auth.iam.IAMLoginModule required;");
        props.put("sasl.client.callback.handler.class",
                "software.amazon.msk.auth.iam.IAMClientCallbackHandler");

        this.producer = new KafkaProducer<>(props);
        log.info("MSK producer initialized: brokers={}",
                bootstrapServers);
    }

    @Override
    public KafkaBridgeResult publish(NormalizedPaymentEvent event) {
        long startMs = System.currentTimeMillis();
        try {
            String payload = mapper.writeValueAsString(event);

            ProducerRecord<String, String> record =
                    new ProducerRecord<>(TARGET_TOPIC,
                            event.userId(),    // partition key: userId
                            payload);

            // Synchronous send — Lambda is synchronous
            Future<RecordMetadata> future = producer.send(record);
            RecordMetadata metadata = future.get(10, TimeUnit.SECONDS);

            long durationMs = System.currentTimeMillis() - startMs;

            log.info("MSK publish success: eventId={} " +
                            "partition={} offset={} durationMs={}",
                    event.eventId(), metadata.partition(),
                    metadata.offset(), durationMs);

            return KafkaBridgeResult.success(
                    metadata.topic(),
                    metadata.partition(),
                    metadata.offset(),
                    durationMs);

        } catch (java.util.concurrent.TimeoutException e) {
            return KafkaBridgeResult.failure(
                    FailureReason.KAFKA_BRIDGE_TIMEOUT,
                    "MSK publish timeout after 10s");
        } catch (Exception e) {
            log.error("MSK publish failed: {}", e.getMessage());
            return KafkaBridgeResult.failure(
                    FailureReason.KAFKA_BRIDGE_UNAVAILABLE,
                    e.getMessage());
        }
    }
}
