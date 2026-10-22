package io.github.louch.devdesk.core.queue;

/**
 * A message broker of the project, as DevDesk reaches it from this machine.
 *
 * @param port RabbitMQ: its management HTTP API; Kafka: its client port
 * @param clientPort the port the project's services connect to (RabbitMQ's AMQP port), to see who is connected;
 *     -1 when it isn't published
 * @param vhost RabbitMQ's virtual host ("/" by default); empty for Kafka
 */
public record Broker(
        String service, Kind kind, String host, int port, int clientPort, String user, String password, String vhost) {

    /** Which broker. */
    public enum Kind {
        RABBITMQ,
        KAFKA,
        /** Amazon SQS's API, from a local stand-in (ElasticMQ, LocalStack). */
        SQS
    }

    /** Principle 7: the password never ends up in a log line. */
    @Override
    public String toString() {
        return kind + " " + service + " at " + host + ":" + port;
    }
}
