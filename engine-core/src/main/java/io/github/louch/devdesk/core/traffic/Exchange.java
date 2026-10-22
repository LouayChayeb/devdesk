package io.github.louch.devdesk.core.traffic;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One recorded call: the request as it came in and the answer the service gave.
 *
 * @param caller the project service that made the call, when DevDesk could tell from the connection; else "" (a
 *     browser, curl, something outside the project)
 * @param path the path and query, as sent ("/orders?status=open")
 */
public record Exchange(
        int seq,
        Instant at,
        String caller,
        String method,
        String path,
        Map<String, List<String>> requestHeaders,
        String requestBody,
        int status,
        Map<String, List<String>> responseHeaders,
        String responseBody,
        long ms) {

    public Exchange {
        requestHeaders = Map.copyOf(requestHeaders);
        responseHeaders = Map.copyOf(responseHeaders);
    }
}
