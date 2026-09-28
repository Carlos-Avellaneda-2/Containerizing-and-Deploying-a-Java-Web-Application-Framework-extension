package co.edu.escuelaing.webframework;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ResponseTest {

    @Test
    void serialisesStatusLineHeadersAndBody() {
        String raw = new String(Response.text(404, "404 Not Found").toBytes(), StandardCharsets.UTF_8);

        assertTrue(raw.startsWith("HTTP/1.1 404 Not Found\r\n"));
        assertTrue(raw.contains("Content-Type: text/plain; charset=UTF-8\r\n"));
        assertTrue(raw.contains("Content-Length: 13\r\n"));
        assertTrue(raw.contains("Connection: close\r\n"));
        assertTrue(raw.endsWith("\r\n\r\n404 Not Found"));
    }

    @Test
    void contentLengthCountsBytesNotCharacters() {
        Response response = Response.text(200, "héllo"); // 'é' takes 2 bytes in UTF-8

        String raw = new String(response.toBytes(), StandardCharsets.UTF_8);

        assertTrue(raw.contains("Content-Length: 6\r\n"));
    }

    @Test
    void binaryBodiesAreWrittenUnchanged() {
        byte[] body = {(byte) 0x89, 'P', 'N', 'G', 0, (byte) 0xFF};
        byte[] raw = Response.of(200, "image/png", body).toBytes();

        byte[] tail = java.util.Arrays.copyOfRange(raw, raw.length - body.length, raw.length);
        assertEquals(java.util.Arrays.toString(body), java.util.Arrays.toString(tail));
    }

    @Test
    void handlersCanChangeStatusAndContentType() {
        Response response = new Response();
        response.setStatus(400);
        response.setContentType("application/json");

        assertEquals(400, response.getStatus());
        assertEquals("application/json", response.getContentType());
    }

    @Test
    void customHeadersAreWritten() {
        Response response = new Response();
        response.setHeader("Allow", "GET");

        assertTrue(new String(response.toBytes(), StandardCharsets.UTF_8).contains("Allow: GET\r\n"));
        assertEquals("GET", response.getHeader("allow"));
    }

    @Test
    void headerInjectionIsRejected() {
        Response response = new Response();

        assertThrows(IllegalArgumentException.class,
                () -> response.setHeader("X-Test", "value\r\nSet-Cookie: hacked=1"));
        assertThrows(IllegalArgumentException.class, () -> response.setContentType("text/plain\r\nX: y"));
    }

    @Test
    void serverManagedHeadersCannotBeOverridden() {
        Response response = new Response();

        assertThrows(IllegalArgumentException.class, () -> response.setHeader("Content-Length", "5"));
    }

    @Test
    void invalidStatusIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Response().setStatus(42));
    }
}
