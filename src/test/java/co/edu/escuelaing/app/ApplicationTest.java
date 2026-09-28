package co.edu.escuelaing.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.escuelaing.webframework.Config;
import co.edu.escuelaing.webframework.Request;
import co.edu.escuelaing.webframework.Response;
import co.edu.escuelaing.webframework.WebFramework;
import java.net.ConnectException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests of the example application's routes. Most call dispatch() directly (no sockets). */
class ApplicationTest {

    private static final Config DEVELOPMENT = new Config(Map.of("APP_ENV", "development"));
    private static final Config PRODUCTION = new Config(Map.of("APP_ENV", "production"));

    @BeforeEach
    void freshFramework() {
        WebFramework.reset();
    }

    private static Response call(String requestLine) throws Exception {
        return WebFramework.server().dispatch(Request.parse(requestLine));
    }

    // ------------------------------------------------------------------- services

    @Test
    void helloGreetsTheGivenName() throws Exception {
        Application.registerRoutes(DEVELOPMENT);

        Response response = call("GET /hello?name=Pedro HTTP/1.1");

        assertEquals(200, response.getStatus());
        assertEquals("Hello Pedro", response.getBodyAsString());
    }

    @Test
    void helloFallsBackToWorldWhenNameIsMissingOrBlank() throws Exception {
        Application.registerRoutes(DEVELOPMENT);

        assertEquals("Hello world", call("GET /hello HTTP/1.1").getBodyAsString());
        assertEquals("Hello world", call("GET /hello?name= HTTP/1.1").getBodyAsString());
        assertEquals("Hello world", call("GET /hello?name=+++ HTTP/1.1").getBodyAsString());
    }

    @Test
    void greetingPrefixComesFromTheEnvironment() throws Exception {
        Application.registerRoutes(new Config(Map.of("GREETING_PREFIX", "Hola")));

        assertEquals("Hola Pedro", call("GET /hello?name=Pedro HTTP/1.1").getBodyAsString());
    }

    @Test
    void piReturnsMathPi() throws Exception {
        Application.registerRoutes(DEVELOPMENT);

        assertEquals(String.valueOf(Math.PI), call("GET /pi HTTP/1.1").getBodyAsString());
    }

    @Test
    void addSumsTwoParameters() throws Exception {
        Application.registerRoutes(DEVELOPMENT);

        assertEquals("5", call("GET /add?a=2&b=3 HTTP/1.1").getBodyAsString());
        assertEquals("-1", call("GET /add?a=2&b=-3 HTTP/1.1").getBodyAsString());
    }

    @Test
    void addRejectsMissingOrInvalidParametersWith400() throws Exception {
        Application.registerRoutes(DEVELOPMENT);

        assertEquals(400, call("GET /add?a=2 HTTP/1.1").getStatus());
        assertEquals(400, call("GET /add HTTP/1.1").getStatus());
        assertEquals(400, call("GET /add?a=x&b=3 HTTP/1.1").getStatus());
        assertEquals(400, call("GET /add?a=9223372036854775807&b=1 HTTP/1.1").getStatus());
    }

    @Test
    void configEndpointReportsNonSensitiveSettingsAsJson() throws Exception {
        Application.registerRoutes(new Config(Map.of("APP_ENV", "production", "GREETING_PREFIX", "Hola \"amigo\"")));

        Response response = call("GET /config HTTP/1.1");

        assertTrue(response.getContentType().startsWith("application/json"));
        String json = response.getBodyAsString();
        assertTrue(json.contains("\"appEnv\":\"production\""), json);
        assertTrue(json.contains("\"greetingPrefix\":\"Hola \\\"amigo\\\"\""), json); // escaped
        assertTrue(json.contains("\"shutdownEnabled\":false"), json);
    }

    @Test
    void slowAnswersAfterTheRequestedDelay() throws Exception {
        Application.registerRoutes(DEVELOPMENT);

        Response response = call("GET /slow?ms=10 HTTP/1.1");

        assertEquals(200, response.getStatus());
        assertTrue(response.getBodyAsString().startsWith("Done after 10 ms"));
    }

    @Test
    void slowRejectsInvalidDelays() throws Exception {
        Application.registerRoutes(DEVELOPMENT);

        assertEquals(400, call("GET /slow?ms=-1 HTTP/1.1").getStatus());
        assertEquals(400, call("GET /slow?ms=abc HTTP/1.1").getStatus());
        assertEquals(400, call("GET /slow?ms=999999 HTTP/1.1").getStatus());
    }

    @Test
    void statusReportsActiveRequestsAsJson() throws Exception {
        Application.registerRoutes(DEVELOPMENT);

        Response response = call("GET /status HTTP/1.1");

        assertTrue(response.getContentType().startsWith("application/json"));
        assertTrue(response.getBodyAsString().contains("\"activeRequests\":"));
    }

    // ------------------------------------------------------ static files & fallback

    @Test
    void staticFilesAreServedWhenNoRouteMatches() throws Exception {
        Application.registerRoutes(DEVELOPMENT);

        assertEquals(200, call("GET /index.html HTTP/1.1").getStatus());
        assertEquals("image/png", call("GET /images/logo.png HTTP/1.1").getContentType());
    }

    @Test
    void unknownPathReturns404() throws Exception {
        Application.registerRoutes(DEVELOPMENT);

        Response response = call("GET /unknown HTTP/1.1");

        assertEquals(404, response.getStatus());
        assertEquals("404 Not Found", response.getBodyAsString());
    }

    @Test
    void staticFilesPathVariableSelectsAnExternalFolder(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("external.txt"), "served from STATIC_FILES_PATH");
        Application.registerRoutes(new Config(Map.of("STATIC_FILES_PATH", dir.toString())));

        assertEquals("served from STATIC_FILES_PATH", call("GET /external.txt HTTP/1.1").getBodyAsString());
        assertEquals(404, call("GET /index.html HTTP/1.1").getStatus()); // packaged webroot not used
    }

    // ------------------------------------------------------- /shutdown per environment

    @Test
    void shutdownRouteExistsInDevelopment() throws Exception {
        Application.registerRoutes(DEVELOPMENT);

        Response response = call("GET /shutdown HTTP/1.1");

        assertEquals(200, response.getStatus());
        assertEquals("Server will stop after this response.", response.getBodyAsString());
    }

    @Test
    void shutdownRouteExistsWhenAppEnvIsNotSet() throws Exception {
        Application.registerRoutes(new Config(Map.of())); // default APP_ENV = development

        assertEquals(200, call("GET /shutdown HTTP/1.1").getStatus());
    }

    @Test
    void shutdownRouteIsNotAvailableInProduction() throws Exception {
        Application.registerRoutes(PRODUCTION);

        assertEquals(404, call("GET /shutdown HTTP/1.1").getStatus());
    }

    @Test
    void shutdownStopsARealServerGracefullyInDevelopment() throws Exception {
        Application.registerRoutes(DEVELOPMENT);
        Thread serverThread = new Thread(() -> {
            try {
                WebFramework.start(0);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        serverThread.start();
        assertTrue(WebFramework.server().awaitStarted(5, TimeUnit.SECONDS));
        int port = WebFramework.server().getPort();

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/shutdown")).build(),
                HttpResponse.BodyHandlers.ofString());
        serverThread.join(5_000);

        assertEquals(200, response.statusCode());
        assertFalse(serverThread.isAlive());
        assertThrows(ConnectException.class, () -> new Socket("127.0.0.1", port).close());
    }

    @Test
    void shutdownReturns404OverHttpInProductionAndTheServerKeepsRunning() throws Exception {
        Application.registerRoutes(PRODUCTION);
        Thread serverThread = new Thread(() -> {
            try {
                WebFramework.start(0);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        serverThread.start();
        assertTrue(WebFramework.server().awaitStarted(5, TimeUnit.SECONDS));
        int port = WebFramework.server().getPort();
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> shutdown = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/shutdown")).build(),
                HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> hello = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/hello?name=Cloud")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(404, shutdown.statusCode());
        assertEquals("Hello Cloud", hello.body());
        assertTrue(WebFramework.server().isRunning());

        WebFramework.stop(); // cleanup: stop from another thread unblocks accept()
        serverThread.join(5_000);
        assertFalse(serverThread.isAlive());
    }
}
