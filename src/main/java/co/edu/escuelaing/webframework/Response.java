package co.edu.escuelaing.webframework;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * An HTTP response under construction: status, content type, headers and body.
 *
 * <p>Route handlers receive an instance and may tweak it (for example
 * {@code resp.setStatus(400)}); the server serialises it with {@link #toBytes()}.
 * The body is stored as bytes so text and binary content (images) share one path.
 */
public final class Response {

    private static final String DEFAULT_CONTENT_TYPE = "text/plain; charset=UTF-8";

    /** Headers the server writes itself; letting handlers set them would create duplicates. */
    private static final Set<String> RESERVED_HEADERS =
            Set.of("content-type", "content-length", "connection");

    private int status = 200;
    private String contentType = DEFAULT_CONTENT_TYPE;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private byte[] body = new byte[0];

    /** Creates an empty {@code 200 OK} response with {@code text/plain} content. */
    public Response() {
    }

    /** Convenience factory for a plain-text response. */
    public static Response text(int status, String body) {
        Response response = new Response();
        response.setStatus(status);
        response.setBody(body);
        return response;
    }

    /** Convenience factory for an arbitrary (possibly binary) response. */
    public static Response of(int status, String contentType, byte[] body) {
        Response response = new Response();
        response.setStatus(status);
        response.setContentType(contentType);
        response.setBody(body);
        return response;
    }

    public void setStatus(int status) {
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException("Invalid HTTP status code: " + status);
        }
        this.status = status;
    }

    public void setContentType(String contentType) {
        this.contentType = requireSafeHeaderValue(contentType);
    }

    /** Adds or replaces a custom header (e.g. {@code Cache-Control}). */
    public void setHeader(String name, String value) {
        requireSafeHeaderValue(name);
        requireSafeHeaderValue(value);
        if (RESERVED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException(
                    "Header '" + name + "' is managed by the server; use setContentType/setBody");
        }
        headers.put(name, value);
    }

    public void setBody(String body) {
        this.body = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
    }

    public void setBody(byte[] body) {
        this.body = body == null ? new byte[0] : body;
    }

    public int getStatus() {
        return status;
    }

    public String getContentType() {
        return contentType;
    }

    public String getHeader(String name) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    public byte[] getBody() {
        return body;
    }

    public String getBodyAsString() {
        return new String(body, StandardCharsets.UTF_8);
    }

    /** Serialises the full HTTP/1.1 message (status line, headers, blank line, body). */
    public byte[] toBytes() {
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(status).append(' ').append(reasonPhrase(status)).append("\r\n");
        head.append("Content-Type: ").append(contentType).append("\r\n");
        head.append("Content-Length: ").append(body.length).append("\r\n");
        head.append("Connection: close\r\n");
        head.append("X-Content-Type-Options: nosniff\r\n");
        headers.forEach((name, value) -> head.append(name).append(": ").append(value).append("\r\n"));
        head.append("\r\n");

        ByteArrayOutputStream out = new ByteArrayOutputStream(head.length() + body.length);
        out.writeBytes(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.writeBytes(body);
        return out.toByteArray();
    }

    /** Rejects CR/LF to prevent HTTP header (response-splitting) injection. */
    private static String requireSafeHeaderValue(String value) {
        if (value == null || value.isBlank() || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Header name/value must be non-blank and contain no line breaks");
        }
        return value;
    }

    static String reasonPhrase(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 204 -> "No Content";
            case 301 -> "Moved Permanently";
            case 302 -> "Found";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 500 -> "Internal Server Error";
            case 501 -> "Not Implemented";
            case 503 -> "Service Unavailable";
            default -> "Status " + status;
        };
    }
}
