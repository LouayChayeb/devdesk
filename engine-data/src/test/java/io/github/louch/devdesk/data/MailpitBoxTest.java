package io.github.louch.devdesk.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.github.louch.devdesk.core.data.DataException;
import io.github.louch.devdesk.core.mail.MailBox;
import io.github.louch.devdesk.core.mail.MailCatcher;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Against a fake Mailpit that answers what the real one (v1.2x) answers, captured from examples/full-stack. */
class MailpitBoxTest {

    private HttpServer server;
    private MailCatcher catcher;
    private final List<String> seen = new ArrayList<>();
    private final MailpitBox box = new MailpitBox();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.add(ex.getRequestMethod() + " " + ex.getRequestURI() + (body.isEmpty() ? "" : " " + body));
            String path = ex.getRequestURI().getPath();
            String answer = switch (path) {
                case "/api/v1/messages", "/api/v1/search" ->
                    ex.getRequestMethod().equals("DELETE") ? "ok" : LIST;
                case "/api/v1/message/abc" -> MESSAGE;
                case "/api/v1/message/abc/headers" -> "{\"Subject\":[\"Order #90\"],\"To\":[\"you@example.test\"]}";
                case "/api/v1/message/abc/part/2" -> "receipt";
                default -> null;
            };
            byte[] out = (answer == null ? "not found" : answer).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(answer == null ? 404 : 200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        catcher = new MailCatcher(
                "mailpit", "http://127.0.0.1:" + server.getAddress().getPort(), 1025);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void theInboxListsNewestFirstWithTotals() {
        MailBox.Page page = box.list(catcher, "", 50);

        assertThat(page.total()).isEqualTo(18);
        assertThat(page.unread()).isEqualTo(17);
        assertThat(page.mails()).hasSize(1);
        MailBox.Summary s = page.mails().getFirst();
        assertThat(s.subject()).isEqualTo("Order #90 shipped");
        assertThat(s.from().toString()).isEqualTo("Shop <shop@example.test>");
        assertThat(s.to()).extracting(MailBox.Address::address).containsExactly("you@example.test");
        assertThat(s.at()).isEqualTo(Instant.parse("2026-10-08T11:09:03.879Z"));
        assertThat(seen).containsExactly("GET /api/v1/messages?limit=50");
    }

    @Test
    void searchWordsGoToMailpitsSearch() {
        box.list(catcher, " to:ada order ", 20);

        assertThat(seen).containsExactly("GET /api/v1/search?limit=20&query=to%3Aada+order");
    }

    @Test
    void aMailHasBothBodiesHeadersAndAttachments() {
        MailBox.Mail m = box.read(catcher, "abc");

        assertThat(m.text()).isEqualTo("Job 90 finished.\r\n");
        assertThat(m.html()).contains("<b>90</b>");
        assertThat(m.headers()).contains(new MailBox.Header("Subject", "Order #90"));
        assertThat(m.attachments()).containsExactly(new MailBox.Attachment("2", "receipt.txt", "text/plain", 7));
        assertThat(m.summary().attachments()).isEqualTo(1);
        assertThat(new String(box.attachment(catcher, "abc", "2"), StandardCharsets.UTF_8))
                .isEqualTo("receipt");
    }

    @Test
    void deletingSomeSendsTheirIdsAndAllSendsNone() {
        box.delete(catcher, List.of("abc"));
        box.delete(catcher, List.of());

        assertThat(seen).containsExactly("DELETE /api/v1/messages {\"IDs\":[\"abc\"]}", "DELETE /api/v1/messages");
    }

    @Test
    void anUnknownMailAndAStoppedMailpitSayWhatToDo() throws IOException {
        assertThatThrownBy(() -> box.read(catcher, "gone"))
                .isInstanceOf(DataException.class)
                .hasMessageContaining("deleted meanwhile");
        int free;
        try (ServerSocket s = new ServerSocket(0)) {
            free = s.getLocalPort();
        }
        var stopped = new MailCatcher("mailpit", "http://127.0.0.1:" + free, 1025);
        assertThatThrownBy(() -> box.list(stopped, "", 10)).hasMessageContaining("is mailpit running?");
    }

    private static final String LIST = """
            {"total":18,"unread":17,"count":1,"messages_count":18,"start":0,"tags":[],"messages":[
              {"ID":"3506xrH4LsL5cE727k3svc","MessageID":"0LaC@mailpit","Read":false,
               "From":{"Name":"Shop","Address":"shop@example.test"},"To":[{"Name":"","Address":"you@example.test"}],
               "Cc":null,"Bcc":null,"ReplyTo":[],"Subject":"Order #90 shipped","Created":"2026-10-08T11:09:03.879Z",
               "Tags":[],"Size":464,"Attachments":0,"Snippet":"Job 90 finished at 12:09:03."}]}
            """;

    private static final String MESSAGE = """
            {"ID":"abc","From":{"Name":"","Address":"shop@example.test"},"To":[{"Name":"","Address":"you@example.test"}],
             "Cc":[],"Subject":"Order #90 shipped","Date":"2026-10-08T11:09:03.879Z","Text":"Job 90 finished.\\r\\n",
             "HTML":"<p>Job <b>90</b></p>","Size":464,"Inline":[],
             "Attachments":[{"PartID":"2","FileName":"receipt.txt","ContentType":"text/plain","Size":7}]}
            """;
}
