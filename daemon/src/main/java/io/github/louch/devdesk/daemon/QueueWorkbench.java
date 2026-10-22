package io.github.louch.devdesk.daemon;

import io.github.louch.devdesk.core.data.DataException;
import io.github.louch.devdesk.core.queue.Broker;
import io.github.louch.devdesk.core.queue.Brokers;
import io.github.louch.devdesk.core.queue.MessageBroker;
import io.github.louch.devdesk.core.state.ServiceState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * The Inbox page's queues: what waits in the project's RabbitMQ queues and Kafka topics, and who reads them. Only a
 * broker of the open project is ever reached, found from devdesk.yaml like the databases are.
 */
final class QueueWorkbench {

    /** At most this many messages shown at once: enough to see what the app sends. */
    static final int MAX_PEEK = 100;

    private final EngineSession session;
    private final Map<Broker.Kind, MessageBroker> brokers;

    QueueWorkbench(EngineSession session, Map<Broker.Kind, MessageBroker> brokers) {
        this.session = session;
        this.brokers = brokers;
    }

    /** The project's brokers, whether each runs, and which services are connected to it from this machine. */
    EngineSession.Answer brokers() {
        Optional<EngineSession.Current> current = session.current();
        if (current.isEmpty()) {
            return new EngineSession.Answer(404, ApiJson.error("no project is open"));
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (Broker b : Brokers.find(current.get().project())) {
            ServiceState state = current.get().states().getOrDefault(b.service(), new ServiceState.Stopped());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("service", b.service());
            m.put("kind", b.kind().name().toLowerCase());
            m.put("port", b.port());
            m.put("clientPort", b.clientPort());
            m.put("state", ApiJson.state(state));
            m.put("connected", session.connectedTo(b.clientPort()));
            list.add(m);
        }
        return new EngineSession.Answer(200, Map.of("brokers", list));
    }

    EngineSession.Answer queues(String service) {
        return with(
                service,
                (b, broker) -> Map.of(
                        "queues",
                        broker.queues(b).stream()
                                .map(q -> Map.of(
                                        "name",
                                        q.name(),
                                        "waiting",
                                        q.waiting(),
                                        "inFlight",
                                        q.inFlight(),
                                        "readers",
                                        q.readers()))
                                .toList()));
    }

    EngineSession.Answer messages(String service, String queue, int max) {
        return with(
                service,
                (b, broker) -> Map.of(
                        "messages",
                        broker.peek(b, queue, Math.clamp(max, 1, MAX_PEEK)).stream()
                                .map(m -> {
                                    Map<String, Object> out = new LinkedHashMap<>();
                                    out.put("position", m.position());
                                    out.put("at", m.at().map(Object::toString).orElse(null));
                                    out.put("key", m.key().orElse(null));
                                    out.put("body", m.body());
                                    out.put("headers", m.headers());
                                    return out;
                                })
                                .toList()));
    }

    EngineSession.Answer readers(String service, String queue) {
        return with(
                service,
                (b, broker) -> Map.of(
                        "readers",
                        broker.readers(b, queue).stream()
                                .map(r -> Map.of(
                                        "name", r.name(), "client", r.client(), "from", r.from(), "behind", r.behind()))
                                .toList(),
                        "connected",
                        session.connectedTo(b.clientPort())));
    }

    /** {body, key?}: one message, sent on the user's explicit click. */
    EngineSession.Answer publish(String service, String queue, Map<?, ?> body) {
        if (!(body.get("body") instanceof String text)) {
            return new EngineSession.Answer(400, ApiJson.error("send {\"body\": \"...\", \"key\"?: \"...\"}"));
        }
        Optional<String> key = body.get("key") instanceof String k && !k.isBlank() ? Optional.of(k) : Optional.empty();
        return with(service, (b, broker) -> {
            broker.publish(b, queue, text, key);
            return Map.of("sent", true);
        });
    }

    private EngineSession.Answer with(String service, BiFunction<Broker, MessageBroker, Object> call) {
        Optional<EngineSession.Current> current = session.current();
        if (current.isEmpty()) {
            return new EngineSession.Answer(404, ApiJson.error("no project is open"));
        }
        Optional<Broker> b = Brokers.find(current.get().project(), service);
        if (b.isEmpty()) {
            return new EngineSession.Answer(
                    404,
                    ApiJson.error(service + " isn't a RabbitMQ (with its management port) or Kafka of this project"));
        }
        try {
            return new EngineSession.Answer(
                    200, call.apply(b.get(), brokers.get(b.get().kind())));
        } catch (DataException e) {
            return new EngineSession.Answer(502, ApiJson.error(e.getMessage()));
        }
    }
}
