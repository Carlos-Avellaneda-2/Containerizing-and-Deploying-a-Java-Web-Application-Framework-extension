package co.edu.escuelaing.webframework;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RequestTest {

    @Test
    void parsesMethodPathAndVersion() throws Exception {
        Request request = Request.parse("GET /hello HTTP/1.1");

        assertEquals("GET", request.getMethod());
        assertEquals("/hello", request.getPath());
        assertEquals("HTTP/1.1", request.getVersion());
        assertTrue(request.getQueryParams().isEmpty());
    }

    @Test
    void extractsASingleQueryParameter() throws Exception {
        Request request = Request.parse("GET /hello?name=Pedro HTTP/1.1");

        assertEquals("/hello", request.getPath());
        assertEquals("Pedro", request.getValue("name"));
    }

    @Test
    void extractsMultipleQueryParameters() throws Exception {
        Request request = Request.parse("GET /hello?name=Pedro&language=en HTTP/1.1");

        assertEquals("Pedro", request.getValue("name"));
        assertEquals("en", request.getValue("language"));
        assertEquals(2, request.getQueryParams().size());
    }

    @Test
    void missingParameterReturnsNullInsteadOfFailing() throws Exception {
        Request request = Request.parse("GET /hello HTTP/1.1");

        assertNull(request.getValue("name"));
        assertEquals("world", request.getValue("name", "world"));
    }

    @Test
    void decodesUrlEncodedValues() throws Exception {
        Request request = Request.parse("GET /hello?name=Jos%C3%A9+Luis&x=a%26b HTTP/1.1");

        assertEquals("José Luis", request.getValue("name"));
        assertEquals("a&b", request.getValue("x"));
    }

    @Test
    void parameterWithoutValueIsAnEmptyString() throws Exception {
        Request request = Request.parse("GET /hello?flag&name= HTTP/1.1");

        assertEquals("", request.getValue("flag"));
        assertEquals("", request.getValue("name"));
    }

    @Test
    void whenAParameterRepeatsTheFirstValueWins() throws Exception {
        Request request = Request.parse("GET /hello?name=Ana&name=Luis HTTP/1.1");

        assertEquals("Ana", request.getValue("name"));
    }

    @Test
    void emptyQueryStringIsAccepted() throws Exception {
        Request request = Request.parse("GET /hello? HTTP/1.1");

        assertEquals("/hello", request.getPath());
        assertTrue(request.getQueryParams().isEmpty());
    }

    @Test
    void pathIsPercentDecodedButPlusStaysAPlus() throws Exception {
        assertEquals("/my file.txt", Request.parse("GET /my%20file.txt HTTP/1.1").getPath());
        assertEquals("/a+b", Request.parse("GET /a+b HTTP/1.1").getPath());
    }

    @Test
    void headerLookupIsCaseInsensitive() throws Exception {
        Request request = Request.parse("GET / HTTP/1.1", Map.of("host", "example.com"));

        assertEquals("example.com", request.getHeader("Host"));
        assertNull(request.getHeader("Accept"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "   ",
            "GET",
            "GET /hello",
            "GET /hello HTTP/1.1 extra",
            "GET hello HTTP/1.1",
            "GET /hello FTP/1.0",
            "get /hello HTTP/1.1",
            "GET /hello?name=%ZZ HTTP/1.1",
            "GET /%ZZ HTTP/1.1"
    })
    void malformedRequestLinesAreRejected(String line) {
        assertThrows(MalformedRequestException.class, () -> Request.parse(line));
    }

    @Test
    void nullRequestLineIsRejected() {
        assertThrows(MalformedRequestException.class, () -> Request.parse(null));
    }
}
