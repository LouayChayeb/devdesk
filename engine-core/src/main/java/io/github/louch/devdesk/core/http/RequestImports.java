package io.github.louch.devdesk.core.http;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Requests from other tools: a Postman collection file, or a service's OpenAPI (Swagger) description, which NestJS,
 * FastAPI, Spring and others publish. Each becomes a request of the API client, grouped in collections.
 */
public final class RequestImports {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> METHODS = List.of("get", "post", "put", "patch", "delete", "head", "options");
    /** Nested schemas are followed this deep for an example body; deeper ones become {}. */
    private static final int MAX_DEPTH = 5;

    private RequestImports() {}

    /** One imported request; the caller gives it an id and saves it. */
    public record Imported(String name, String collection, HttpCall call) {}

    /**
     * A Postman collection (format v2.0 or v2.1). Folders become collections named "Collection / Folder". Postman's
     * {{variables}} use the same syntax, so they keep working once defined under Variables.
     *
     * @throws IllegalArgumentException when the text isn't a Postman collection
     */
    public static List<Imported> postman(String text) {
        JsonNode root = parse(text);
        if (!root.has("item") || !root.path("info").has("name")) {
            throw new IllegalArgumentException("that isn't a Postman collection (no \"info\" and \"item\" in it)");
        }
        List<Imported> out = new ArrayList<>();
        postmanItems(root.get("item"), root.path("info").path("name").asString("Postman"), out);
        return out;
    }

    private static void postmanItems(JsonNode items, String collection, List<Imported> out) {
        for (JsonNode item : items) {
            String name = item.path("name").asString("request");
            if (item.has("item")) {
                postmanItems(item.get("item"), collection + " / " + name, out);
                continue;
            }
            JsonNode r = item.path("request");
            if (r.isString()) { // v2.0 allowed a bare URL
                out.add(new Imported(name, collection, new HttpCall("GET", r.asString(), List.of(), Optional.empty())));
                continue;
            }
            JsonNode url = r.path("url");
            String raw = url.isString() ? url.asString() : url.path("raw").asString("");
            List<HttpCall.Header> headers = new ArrayList<>();
            for (JsonNode h : r.path("header")) {
                if (!h.path("disabled").asBoolean(false)) {
                    headers.add(new HttpCall.Header(
                            h.path("key").asString(), h.path("value").asString("")));
                }
            }
            JsonNode body = r.path("body");
            HttpCall.Body b = switch (body.path("mode").asString("")) {
                case "raw" -> new HttpCall.Body.Text(body.path("raw").asString(""));
                case "urlencoded" -> new HttpCall.Body.Form(postmanFields(body.path("urlencoded")));
                case "formdata" -> new HttpCall.Body.Multipart(postmanFields(body.path("formdata")));
                default -> new HttpCall.Body.None();
            };
            out.add(new Imported(
                    name,
                    collection,
                    new HttpCall(
                            r.path("method").asString("GET").toUpperCase(Locale.ROOT),
                            raw,
                            headers,
                            b,
                            postmanAuth(r.path("auth")))));
        }
    }

    private static List<HttpCall.Field> postmanFields(JsonNode list) {
        List<HttpCall.Field> out = new ArrayList<>();
        for (JsonNode f : list) {
            if (f.path("disabled").asBoolean(false)) {
                continue;
            }
            boolean file = f.path("type").asString("").equals("file");
            String value = file
                    ? (f.path("src").isArray()
                            ? f.path("src").path(0).asString("")
                            : f.path("src").asString(""))
                    : f.path("value").asString("");
            out.add(new HttpCall.Field(f.path("key").asString(), value, file));
        }
        return out;
    }

    /** Postman keeps auth values as a list of {key, value}. */
    private static HttpCall.Auth postmanAuth(JsonNode auth) {
        String type = auth.path("type").asString("");
        return switch (type) {
            case "bearer" -> new HttpCall.Auth.Bearer(postmanValue(auth.path("bearer"), "token"));
            case "basic" ->
                new HttpCall.Auth.Basic(
                        postmanValue(auth.path("basic"), "username"), postmanValue(auth.path("basic"), "password"));
            default -> new HttpCall.Auth.None();
        };
    }

    private static String postmanValue(JsonNode list, String key) {
        for (JsonNode e : list) {
            if (e.path("key").asString("").equals(key)) {
                return e.path("value").asString("");
            }
        }
        return "";
    }

    /**
     * An OpenAPI 3 or Swagger 2 description. Each operation becomes a request: path parameters become
     * {@code {{name}}} variables, a JSON body gets an example built from its schema, and secured operations get the
     * matching auth ({@code {{token}}}).
     *
     * @param baseUrl where the service is, as a variable: "{{api.url}}"
     * @throws IllegalArgumentException when the text isn't an OpenAPI description
     */
    public static Description openApi(String text, String baseUrl) {
        JsonNode root = parse(text);
        boolean v3 = root.has("openapi");
        if (!v3 && !root.has("swagger")) {
            throw new IllegalArgumentException(
                    "that isn't an OpenAPI description (no \"openapi\" or \"swagger\" field)");
        }
        String title = root.path("info").path("title").asString("API");
        String prefix = v3
                ? serverPath(root.path("servers").path(0).path("url").asString(""))
                : root.path("basePath").asString("");
        prefix = prefix.equals("/") ? "" : prefix.replaceAll("/$", "");
        List<Imported> out = new ArrayList<>();
        for (var path : root.path("paths").properties()) {
            JsonNode ops = path.getValue();
            for (String method : METHODS) {
                JsonNode op = ops.get(method);
                if (op == null) {
                    continue;
                }
                List<JsonNode> params = new ArrayList<>();
                ops.path("parameters").forEach(params::add); // shared by every method of this path
                op.path("parameters").forEach(p -> params.add(resolve(root, p)));
                String url = baseUrl + prefix + path.getKey().replaceAll("\\{([^}]+)}", "{{$1}}");
                List<String> query = new ArrayList<>();
                List<HttpCall.Header> headers = new ArrayList<>();
                Optional<String> body = Optional.empty();
                for (JsonNode p : params) {
                    String name = p.path("name").asString();
                    String in = p.path("in").asString();
                    // Optional query parameters are left out: the request should work as it is.
                    if (in.equals("query") && p.path("required").asBoolean(false)) {
                        query.add(name + "=" + example(p).orElse("{{" + name + "}}"));
                    } else if (in.equals("header") && p.path("required").asBoolean(false)) {
                        headers.add(new HttpCall.Header(name, example(p).orElse("{{" + name + "}}")));
                    } else if (in.equals("body")) { // Swagger 2
                        body = Optional.of(pretty(sample(root, p.path("schema"), 0)));
                    }
                }
                JsonNode json =
                        resolve(root, op.path("requestBody")).path("content").path("application/json");
                if (!json.isMissingNode()) {
                    body = Optional.of(
                            pretty(json.has("example") ? json.get("example") : sample(root, json.path("schema"), 0)));
                }
                if (body.isPresent()) {
                    headers.add(new HttpCall.Header("Content-Type", "application/json"));
                }
                if (!query.isEmpty()) {
                    url = url + "?" + String.join("&", query);
                }
                String name = op.path("summary").asString("");
                if (name.isBlank()) {
                    name = op.path("operationId").asString(method.toUpperCase(Locale.ROOT) + " " + path.getKey());
                }
                String tag = op.path("tags").path(0).asString("");
                out.add(new Imported(
                        name,
                        tag.isBlank() ? title : title + " / " + tag,
                        new HttpCall(
                                method.toUpperCase(Locale.ROOT),
                                url,
                                headers,
                                body.<HttpCall.Body>map(HttpCall.Body.Text::new).orElse(new HttpCall.Body.None()),
                                auth(root, op))));
            }
        }
        return new Description(title, out);
    }

    /** @param title the API's own name ("Shop API"), also the collection's */
    public record Description(String title, List<Imported> requests) {}

    /** Where NestJS, FastAPI, Spring and others publish their description, in the order to try them. */
    public static final List<String> OPENAPI_PATHS = List.of(
            "/api-json", // NestJS: SwaggerModule.setup("api", ...)
            "/openapi.json", // FastAPI
            "/v3/api-docs", // Spring (springdoc)
            "/swagger.json",
            "/docs-json", // NestJS with setup("docs")
            "/api/docs-json",
            "/swagger/v1/swagger.json", // ASP.NET
            "/api-docs",
            "/openapi");

    /** "http://localhost:3000/api" → "/api"; a relative server URL is already a path. */
    private static String serverPath(String url) {
        if (url.startsWith("http://") || url.startsWith("https://")) {
            int slash = url.indexOf('/', url.indexOf("//") + 2);
            return slash < 0 ? "" : url.substring(slash);
        }
        return url;
    }

    private static HttpCall.Auth auth(JsonNode root, JsonNode op) {
        JsonNode security = op.has("security") ? op.get("security") : root.path("security");
        JsonNode schemes = root.has("components")
                ? root.path("components").path("securitySchemes")
                : root.path("securityDefinitions");
        for (JsonNode requirement : security) {
            for (var entry : requirement.properties()) {
                JsonNode scheme = schemes.path(entry.getKey());
                String type = scheme.path("type").asString("");
                String httpScheme = scheme.path("scheme").asString("").toLowerCase(Locale.ROOT);
                if ((type.equals("http") && httpScheme.equals("bearer")) || type.equals("oauth2")) {
                    return new HttpCall.Auth.Bearer("{{token}}");
                }
                if ((type.equals("http") && httpScheme.equals("basic")) || type.equals("basic")) {
                    return new HttpCall.Auth.Basic("{{user}}", "{{password}}");
                }
            }
        }
        return new HttpCall.Auth.None();
    }

    private static Optional<String> example(JsonNode param) {
        JsonNode e = param.has("example")
                ? param.get("example")
                : param.path("schema").path("example");
        if (e.isMissingNode() || e.isNull()) {
            return Optional.empty();
        }
        return Optional.of(e.isString() ? e.asString() : e.toString());
    }

    /** Follows {@code $ref: "#/components/schemas/Order"} inside the same document. */
    private static JsonNode resolve(JsonNode root, JsonNode node) {
        JsonNode n = node;
        for (int i = 0; i < 10 && n.has("$ref"); i++) {
            String ref = n.get("$ref").asString("");
            if (!ref.startsWith("#/")) {
                return n;
            }
            n = root.at(ref.substring(1));
        }
        return n;
    }

    /** An example value for a schema: its own example, else one made from its type and properties. */
    static JsonNode sample(JsonNode root, JsonNode schemaRef, int depth) {
        JsonNode s = resolve(root, schemaRef);
        if (s.has("example")) {
            return s.get("example");
        }
        if (s.has("default")) {
            return s.get("default");
        }
        if (s.has("enum") && !s.get("enum").isEmpty()) {
            return s.get("enum").get(0);
        }
        if (s.has("allOf")) {
            ObjectNode merged = JSON.createObjectNode();
            for (JsonNode part : s.get("allOf")) {
                if (sample(root, part, depth + 1) instanceof ObjectNode o) {
                    merged.setAll(o);
                }
            }
            return merged;
        }
        if (s.has("oneOf") || s.has("anyOf")) {
            return sample(root, (s.has("oneOf") ? s.get("oneOf") : s.get("anyOf")).path(0), depth + 1);
        }
        String type = s.path("type").asString(s.has("properties") ? "object" : "");
        if (depth > MAX_DEPTH) {
            return type.equals("array") ? JSON.createArrayNode() : JSON.createObjectNode();
        }
        return switch (type) {
            case "object" -> {
                ObjectNode o = JSON.createObjectNode();
                for (var p : s.path("properties").properties()) {
                    if (!p.getValue().path("readOnly").asBoolean(false)) {
                        o.set(p.getKey(), sample(root, p.getValue(), depth + 1));
                    }
                }
                yield o;
            }
            case "array" -> {
                ArrayNode a = JSON.createArrayNode();
                a.add(sample(root, s.path("items"), depth + 1));
                yield a;
            }
            case "integer" -> JSON.getNodeFactory().numberNode(0);
            case "number" -> JSON.getNodeFactory().numberNode(0.0);
            case "boolean" -> JSON.getNodeFactory().booleanNode(false);
            case "string" ->
                JSON.getNodeFactory().stringNode(stringExample(s.path("format").asString("")));
            default -> JSON.createObjectNode();
        };
    }

    private static final Set<String> DATES = Set.of("date-time", "date");

    private static String stringExample(String format) {
        return switch (format) {
            case "email" -> "user@example.com";
            case "uuid" -> "00000000-0000-0000-0000-000000000000";
            case "uri", "url" -> "https://example.com";
            default ->
                DATES.contains(format) ? (format.equals("date") ? "2026-01-01" : "2026-01-01T00:00:00Z") : "string";
        };
    }

    private static String pretty(JsonNode node) {
        return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node);
    }

    private static JsonNode parse(String text) {
        try {
            JsonNode n = JSON.readTree(text);
            if (n == null || !n.isObject()) {
                throw new IllegalArgumentException("the file isn't a JSON object");
            }
            return n;
        } catch (JacksonException e) {
            throw new IllegalArgumentException("the file isn't valid JSON: " + e.getOriginalMessage());
        }
    }
}
