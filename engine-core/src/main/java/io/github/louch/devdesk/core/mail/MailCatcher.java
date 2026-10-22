package io.github.louch.devdesk.core.mail;

/**
 * A fake mail server of the project: the app sends its e-mails to it over SMTP, and it keeps them instead of
 * delivering them. DevDesk reads them through its web API.
 *
 * @param service the devdesk.yaml service ("mailpit")
 * @param webUrl its web UI and API, reachable from this machine: "http://127.0.0.1:58026"
 * @param smtpPort where the app sends mail, as published on this machine; -1 when unknown
 */
public record MailCatcher(String service, String webUrl, int smtpPort) {}
