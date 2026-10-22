package io.github.louch.devdesk.core.queue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Looks into a broker for the Inbox page: its queues (RabbitMQ) or topics (Kafka), the messages waiting there, and
 * who reads them. Looking never takes a message away; publishing one is an explicit click.
 */
public interface MessageBroker {

    /** Queues or topics, without the broker's own internal ones. */
    List<Queue> queues(Broker broker);

    /** The next messages, without consuming them (RabbitMQ puts them back; Kafka just reads). At most {@code max}. */
    List<Message> peek(Broker broker, String queue, int max);

    /** Sends one message straight to the queue (RabbitMQ: through the default exchange) or topic. */
    void publish(Broker broker, String queue, String body, Optional<String> key);

    /** Who reads the queue: RabbitMQ's consumers, or Kafka's consumer groups. */
    List<Reader> readers(Broker broker, String queue);

    /**
     * @param waiting messages ready to be read (Kafka: the messages in the topic)
     * @param inFlight RabbitMQ: delivered but not acknowledged yet; Kafka: unread by the slowest group (its lag)
     */
    record Queue(String name, long waiting, long inFlight, int readers) {}

    /** @param position where it is: RabbitMQ's place in the queue, Kafka's "partition/offset" */
    record Message(
            String position, Optional<Instant> at, Optional<String> key, String body, Map<String, String> headers) {}

    /**
     * @param name RabbitMQ: the consumer tag; Kafka: the group's name
     * @param client the name the client gave its connection (connection_name, client.id), when it gave one
     * @param from the client's address as the broker sees it
     * @param behind Kafka: messages it hasn't read yet; -1 when the broker doesn't say (RabbitMQ)
     */
    record Reader(String name, String client, String from, long behind) {}
}
