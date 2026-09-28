package co.edu.escuelaing.webframework;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * An immutable, already-parsed HTTP request: method, path, query-string
 * parameters and headers.
 *
 * <p>Parsing lives here (and not in the server loop) so it can be unit-tested
 * without opening any socket.
 */
public final class Request {

    private final String method;
    private final String path;
    private final String version;
    private final Map<String, String> queryParams;
    private final Map<String, String> headers;

    private Request(String method, String path, String version,
                    Map<String, String> queryParams, Map<String, String> headers) {
        this.method = method;
        this.path = path;
        this.version = version;
        this.queryParams = Collections.unmodifiableMap(queryParams);
        this.headers = Collections.unmodifiableMap(headers);
    }

    /** Parses a request line such as {@code GET /hello?name=Pedro HTTP/1.1}. */
    public static Request parse(String requestLine) throws MalformedRequestException {
        return parse(requestLine, Map.of());
    }

    /** Returns a copy of this request carrying the given headers (names in lower case). */
    public Request withHeaders(Map<String, String> newHeaders) {
        return new Request(method, path, version, new LinkedHashMap<>(queryParams),
                new LinkedHashMap<>(newHeaders));
    }

    /**
     * Parses a request line and attaches the headers already read by the server.
     *
     * @param headers header names must be lower-case
     * @throws MalformedRequestException if the line is empty, incomplete or invalid
     */
    public static Request parse(String requestLine, Map<String, String> headers)
            throws MalformedRequestException {

        if (requestLine == null || requestLine.isBlank()) {
            throw new MalformedRequestException("Empty request line");
        }

        String[] parts = requestLine.trim().split("\\s+");
        if (parts.length != 3) {
            throw new MalformedRequestException(
                    "Request line must be: METHOD TARGET HTTP-VERSION");
        }

        String method = parts[0];
        String target = parts[1];
        String version = parts[2];

        if (!method.matches("[A-Z]+")) {
            throw new MalformedRequestException("Invalid HTTP method");
        }
        if (!version.startsWith("HTTP/")) {
            throw new MalformedRequestException("Invalid HTTP version");
        }
        if (!target.startsWith("/")) {
            throw new MalformedRequestException("Request target must start with '/'");
        }

        int queryStart = target.indexOf('?');
        String rawPath = queryStart < 0 ? target : target.substring(0, queryStart);
        String rawQuery = queryStart < 0 ? "" : target.substring(queryStart + 1);

        return new Request(method, decodePath(rawPath), version,
                parseQuery(rawQuery), new LinkedHashMap<>(headers));
    }

    /** Percent-decodes a path. A '+' is a literal plus sign in paths (unlike in queries). */
    private static String decodePath(String rawPath) throws MalformedRequestException {
        try {
            return URLDecoder.decode(rawPath.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new MalformedRequestException("Invalid percent-encoding in path", e);
        }
    }

    /** Splits {@code a=1&b=2} into a map. If a name repeats, the first value wins. */
    private static Map<String, String> parseQuery(String rawQuery)
            throws MalformedRequestException {

        Map<String, String> params = new LinkedHashMap<>();
        if (rawQuery.isEmpty()) {
            return params;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = decodeQueryComponent(eq < 0 ? pair : pair.substring(0, eq));
            String value = eq < 0 ? "" : decodeQueryComponent(pair.substring(eq + 1));
            if (!name.isEmpty()) {
                params.putIfAbsent(name, value);
            }
        }
        return params;
    }

    private static String decodeQueryComponent(String raw) throws MalformedRequestException {
        try {
            return URLDecoder.decode(raw, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new MalformedRequestException("Invalid percent-encoding in query string", e);
        }
    }

    /**
     * Returns the value of a query-string parameter, or {@code null} when the
     * parameter is absent (a missing parameter never makes the server fail).
     */
    public String getValue(String name) {
        return queryParams.get(name);
    }

    /** Same as {@link #getValue(String)} but returns {@code defaultValue} when absent. */
    public String getValue(String name, String defaultValue) {
        return queryParams.getOrDefault(name, defaultValue);
    }

    public Map<String, String> getQueryParams() {
        return queryParams;
    }

    /** Case-insensitive header lookup; {@code null} if the header was not sent. */
    public String getHeader(String name) {
        return headers.get(name.toLowerCase(Locale.ROOT));
    }

    public String getMethod() {
        return method;
    }

    public String getPath() {
        return path;
    }

    public String getVersion() {
        return version;
    }

    @Override
    public String toString() {
        return method + " " + path + (queryParams.isEmpty() ? "" : " " + queryParams);
    }
}
