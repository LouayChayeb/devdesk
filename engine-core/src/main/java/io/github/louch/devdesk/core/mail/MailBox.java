package io.github.louch.devdesk.core.mail;

import java.time.Instant;
import java.util.List;

/**
 * Reads and deletes the e-mails a {@link MailCatcher} kept (port; Mailpit's web API is the adapter). Failures are
 * {@link io.github.louch.devdesk.core.data.DataException}s with a message for people.
 */
public interface MailBox {

    /**
     * The newest mails first.
     *
     * @param search Mailpit's search words ("to:ada subject:order"); blank = everything
     */
    Page list(MailCatcher catcher, String search, int limit);

    /** One whole mail, by the id {@link #list} gave. */
    Mail read(MailCatcher catcher, String id);

    /** One file attached to a mail, as bytes. */
    byte[] attachment(MailCatcher catcher, String id, String partId);

    /** Deletes these mails, or every mail when {@code ids} is empty. */
    void delete(MailCatcher catcher, List<String> ids);

    /** @param total how many mails match, beyond the {@code limit} shown */
    record Page(int total, int unread, List<Summary> mails) {
        public Page {
            mails = List.copyOf(mails);
        }
    }

    /** A line of the inbox. */
    record Summary(
            String id,
            Address from,
            List<Address> to,
            String subject,
            Instant at,
            long size,
            boolean read,
            int attachments,
            String snippet) {
        public Summary {
            to = List.copyOf(to);
        }
    }

    /** {@code "Ada" <ada@example.test>}; the name may be empty. */
    record Address(String name, String address) {
        @Override
        public String toString() {
            return name.isBlank() ? address : name + " <" + address + ">";
        }
    }

    /** A whole mail: both bodies (either may be empty), every header, and what's attached. */
    record Mail(
            Summary summary,
            List<Address> cc,
            String text,
            String html,
            List<Header> headers,
            List<Attachment> attachments) {
        public Mail {
            cc = List.copyOf(cc);
            headers = List.copyOf(headers);
            attachments = List.copyOf(attachments);
        }
    }

    /** One header line; a name can appear several times ("Received"). */
    record Header(String name, String value) {}

    /** @param partId how the catcher names this part, to download it */
    record Attachment(String partId, String name, String contentType, long size) {}
}
