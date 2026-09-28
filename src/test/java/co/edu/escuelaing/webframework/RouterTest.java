package co.edu.escuelaing.webframework;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RouterTest {

    private final Router router = new Router();

    @Test
    void findsARegisteredHandler() throws Exception {
        RouteHandler handler = (req, resp) -> "hi";
        router.register("GET", "/hello", handler);

        assertSame(handler, router.find("GET", "/hello").orElseThrow());
        assertEquals(1, router.size());
    }

    @Test
    void invokingTheFoundLambdaReturnsItsResult() throws Exception {
        router.register("GET", "/hello", (req, resp) -> "Hello " + req.getValue("name"));

        Request request = Request.parse("GET /hello?name=Pedro HTTP/1.1");
        String body = router.find("GET", "/hello").orElseThrow().handle(request, new Response());

        assertEquals("Hello Pedro", body);
    }

    @Test
    void unknownPathIsEmpty() {
        router.register("GET", "/hello", (req, resp) -> "hi");

        assertTrue(router.find("GET", "/unknown").isEmpty());
    }

    @Test
    void methodIsPartOfTheKey() {
        router.register("GET", "/hello", (req, resp) -> "hi");

        assertTrue(router.find("POST", "/hello").isEmpty());
    }

    @Test
    void pathMatchIsExact() {
        router.register("GET", "/hello", (req, resp) -> "hi");

        assertTrue(router.find("GET", "/hello/").isEmpty());
        assertTrue(router.find("GET", "/HELLO").isEmpty());
    }

    @Test
    void registeringTheSameRouteTwiceFails() {
        router.register("GET", "/hello", (req, resp) -> "a");

        assertThrows(IllegalStateException.class,
                () -> router.register("GET", "/hello", (req, resp) -> "b"));
    }

    @Test
    void invalidRegistrationsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> router.register("GET", "hello", (req, resp) -> "x"));
        assertThrows(NullPointerException.class,
                () -> router.register("GET", "/x", null));
    }

    @Test
    void aHandlerReturningNullIsAllowed() throws Exception {
        router.register("GET", "/none", (req, resp) -> null);

        assertNull(router.find("GET", "/none").orElseThrow().handle(null, null));
    }
}
