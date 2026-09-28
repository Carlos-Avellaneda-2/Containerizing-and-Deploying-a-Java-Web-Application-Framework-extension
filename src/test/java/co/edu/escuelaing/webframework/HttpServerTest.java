package co.edu.escuelaing.webframework;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end tests over real TCP sockets. The server runs its accept loop on a background
 * thread so the test itself can act as the client.
 */
class HttpServerTest {

    private static final long SLOW_MILLIS = 1_000;

    /** Released by the /block route when it starts, so a test knows a request is in flight. */
    private final CountDownLatch blockedRequestStarted = new CountDownLatch(1);

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private HttpServer server;
    private Thread serverThread;
    private volatile Throwable serverFailure;
    private int port;

    @BeforeEach
    void startServer() throws Exception {
        server = new HttpServer();
        server.get("/hello", (req, resp) -> {
            String name = req.getValue("name");
            return "Hello " + (name == null || name.isBlank() ? "world" : name);
        });
        server.get("/pi", (req, resp) -> String.valueOf(Math.PI));
        server.get("/echo", (req, resp) -> req.getValue("a") + "|" + req.getValue("b"));
        server.get("/boom", (req, resp) -> {
            throw new IllegalStateException("handler failure");
        });
        server.get("/styles.css", (req, resp) -> "dynamic wins"); // shadows the static file
        server.get("/stop", (req, resp) -> {
            server.stop();
            return "Server will stop after this response.";
        });
        server.get("/slow", (req, resp) -> {
            Thread.sleep(SLOW_MILLIS);
            return "slow done";
        });
        server.get("/block", (req, resp) -> {
            blockedRequestStarted.countDown();
            Thread.sleep(SLOW_MILLIS);
            return "finished during shutdown";
        });

        serverThread = new Thread(() -> {
            try {
                server.start(0); // 0 = any free port
            } catch (Throwable t) {
                serverFailure = t;
            }
        }, "test-server");
        serverThread.start();
        assertTrue(server.awaitStarted(5, TimeUnit.SECONDS), "server did not start");
        port = server.getPort();
    }

    @AfterEach
    void stopServer() throws Exception {
        server.stop();
        serverThread.join(5_000);
        assertFalse(serverThread.isAlive(), "server thread should have ended");
        assertEquals(null, serverFailure);
    }

    // ------------------------------------------------------------- dynamic routes

    @Test
    void dynamicRouteReturnsTheLambdaResult() throws Exception {
        HttpResponse<String> response = get("/hello?name=Pedro");

        assertEquals(200, response.statusCode());
        assertEquals("Hello Pedro", response.body());
        assertEquals("text/plain; charset=UTF-8", response.headers().firstValue("Content-Type").orElseThrow());
    }

    @Test
    void secondDynamicRouteWorks() throws Exception {
        assertEquals(String.valueOf(Math.PI), get("/pi").body());
    }

    @Test
    void multipleQueryParametersReachTheLambda() throws Exception {
        assertEquals("1|2", get("/echo?a=1&b=2").body());
    }

    @Test
    void missingQueryParameterDoesNotBreakTheServer() throws Exception {
        assertEquals("Hello world", get("/hello").body());
        assertEquals("1|null", get("/echo?a=1").body());
    }

    @Test
    void utf8QueryValuesSurviveTheRoundTrip() throws Exception {
        assertEquals("Hello José", get("/hello?name=Jos%C3%A9").body());
    }

    @Test
    void dynamicRouteTakesPrecedenceOverStaticFile() throws Exception {
        assertEquals("dynamic wins", get("/styles.css").body());
    }

    // ---------------------------------------------------------------- static files

    @Test
    void servesHtmlJavaScriptAndRoot() throws Exception {
        HttpResponse<String> html = get("/index.html");
        assertEquals(200, html.statusCode());
        assertTrue(html.headers().firstValue("Content-Type").orElseThrow().startsWith("text/html"));
        assertTrue(html.body().contains("Lambda Web Framework"));

        HttpResponse<String> js = get("/app.js");
        assertEquals(200, js.statusCode());
        assertTrue(js.headers().firstValue("Content-Type").orElseThrow().startsWith("text/javascript"));

        assertEquals(html.body(), get("/").body());
    }

    @Test
    void servesTheImageAsIntactBinary() throws Exception {
        HttpResponse<byte[]> response = getBytes("/images/logo.png");

        byte[] expected;
        try (InputStream in = HttpServerTest.class.getResourceAsStream("/webroot/images/logo.png")) {
            expected = in.readAllBytes();
        }
        assertEquals(200, response.statusCode());
        assertEquals("image/png", response.headers().firstValue("Content-Type").orElseThrow());
        assertArrayEquals(expected, response.body());
        assertEquals(String.valueOf(expected.length),
                response.headers().firstValue("Content-Length").orElseThrow());
    }

    // ------------------------------------------------------------ errors and limits

    @Test
    void unknownResourceReturns404() throws Exception {
        HttpResponse<String> response = get("/unknown");

        assertEquals(404, response.statusCode());
        assertEquals("404 Not Found", response.body());
        assertEquals("text/plain; charset=UTF-8", response.headers().firstValue("Content-Type").orElseThrow());
    }

    @Test
    void missingStaticFileReturns404() throws Exception {
        assertEquals(404, get("/images/missing.png").statusCode());
        assertEquals(404, get("/images").statusCode());
    }

    @Test
    void directoryTraversalReturns404() throws Exception {
        String raw = raw("GET /%2e%2e/%2e%2e/etc/passwd HTTP/1.1\r\nHost: x\r\n\r\n");

        assertTrue(raw.startsWith("HTTP/1.1 404 Not Found"), raw);
    }

    @Test
    void malformedRequestLineGets400AndTheServerKeepsRunning() throws Exception {
        assertTrue(raw("GARBAGE\r\n\r\n").startsWith("HTTP/1.1 400 Bad Request"));
        assertTrue(raw("GET /hello\r\n\r\n").startsWith("HTTP/1.1 400 Bad Request"));
        assertTrue(raw("GET hello HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 400 Bad Request"));
        assertTrue(raw("GET /hello?name=%ZZ HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 400 Bad Request"));
        assertTrue(raw("\r\n").startsWith("HTTP/1.1 400 Bad Request"));

        assertEquals("Hello Pedro", get("/hello?name=Pedro").body()); // still alive
    }

    @Test
    void oversizedRequestLineIsRejectedAndTheServerSurvives() throws Exception {
        try {
            String raw = raw("GET /" + "a".repeat(20_000) + " HTTP/1.1\r\n\r\n");
            assertTrue(raw.isEmpty() || raw.startsWith("HTTP/1.1 400 Bad Request"), raw);
        } catch (SocketException connectionResetWhileUploading) {
            // acceptable: the server closed the connection before reading everything
        }

        assertEquals("Hello world", get("/hello").body());
    }

    @Test
    void incompleteRequestThenDisconnectDoesNotKillTheServer() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.getOutputStream().write("GET /hel".getBytes(StandardCharsets.US_ASCII));
        } // closed abruptly

        assertEquals("Hello world", get("/hello").body());
    }

    @Test
    void nonGetMethodsGet405() throws Exception {
        HttpRequest post = HttpRequest.newBuilder(uri("/hello"))
                .POST(HttpRequest.BodyPublishers.noBody()).build();

        HttpResponse<String> response = client.send(post, HttpResponse.BodyHandlers.ofString());

        assertEquals(405, response.statusCode());
        assertEquals("GET", response.headers().firstValue("Allow").orElseThrow());
    }

    @Test
    void aFailingHandlerGets500AndTheServerKeepsRunning() throws Exception {
        HttpResponse<String> response = get("/boom");

        assertEquals(500, response.statusCode());
        assertEquals("500 Internal Server Error", response.body()); // internals are not leaked
        assertEquals("Hello world", get("/hello").body());
    }

    @Test
    @SuppressWarnings("try") // the socket is only held open on purpose
    void aSilentClientDoesNotBlockOtherClients() throws Exception {
        try (Socket silent = new Socket("127.0.0.1", port)) { // connects, sends nothing
            assertTimeoutPreemptively(Duration.ofSeconds(2),
                    () -> assertEquals("Hello Ana", get("/hello?name=Ana").body()));
        }
    }

    // ------------------------------------------------------------------ concurrency

    @Test
    void slowRequestsAreServedInParallel() throws Exception {
        int clients = 8;
        ExecutorService pool = Executors.newFixedThreadPool(clients);
        try {
            long begin = System.nanoTime();
            List<Future<HttpResponse<String>>> responses = new ArrayList<>();
            for (int i = 0; i < clients; i++) {
                responses.add(pool.submit(() -> get("/slow")));
            }
            for (Future<HttpResponse<String>> response : responses) {
                assertEquals("slow done", response.get(10, TimeUnit.SECONDS).body());
            }
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);

            // sequentially this would take clients * SLOW_MILLIS = 8 s
            assertTrue(elapsedMillis < 3 * SLOW_MILLIS, "took " + elapsedMillis + " ms");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void fastRequestsAreNotBlockedByASlowOne() throws Exception {
        CompletableFuture<HttpResponse<String>> slow = CompletableFuture.supplyAsync(() -> {
            try {
                return get("/slow");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        long begin = System.nanoTime();
        assertEquals(String.valueOf(Math.PI), get("/pi").body());
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);

        assertTrue(elapsedMillis < SLOW_MILLIS / 2, "fast request took " + elapsedMillis + " ms");
        assertEquals("slow done", slow.get(5, TimeUnit.SECONDS).body());
    }

    // ------------------------------------------------------------ graceful shutdown

    @Test
    void shutdownRouteStopsTheServerAfterSendingItsResponse() throws Exception {
        HttpResponse<String> response = get("/stop");

        // 1. the response of the very request that stopped the server was delivered intact
        assertEquals(200, response.statusCode());
        assertEquals("Server will stop after this response.", response.body());

        // 2. the loop exits and the server thread ends by itself
        serverThread.join(5_000);
        assertFalse(serverThread.isAlive());
        assertFalse(server.isRunning());
        assertEquals(null, serverFailure);

        // 3. the ServerSocket is closed: nobody is listening any more
        assertThrows(ConnectException.class, () -> new Socket("127.0.0.1", port).close());
    }

    @Test
    void stopCalledFromAnotherThreadUnblocksTheServer() throws Exception {
        server.stop();

        serverThread.join(5_000);
        assertFalse(serverThread.isAlive());
        assertFalse(server.isRunning());
        assertTrue(server.awaitStopped(0, TimeUnit.SECONDS));
    }

    @Test
    void stopWaitsForInFlightRequestsAndRefusesNewOnes() throws Exception {
        CompletableFuture<HttpResponse<String>> inFlight = CompletableFuture.supplyAsync(() -> {
            try {
                return get("/block");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertTrue(blockedRequestStarted.await(5, TimeUnit.SECONDS));
        assertEquals(1, server.getActiveRequests());

        server.stop();

        // no new connection is accepted while the old request is still running
        assertThrows(ConnectException.class, () -> new Socket("127.0.0.1", port).close());
        assertFalse(server.awaitStopped(100, TimeUnit.MILLISECONDS), "must wait for the request");

        // the in-flight request completes normally, then the server finishes stopping
        HttpResponse<String> response = inFlight.get(5, TimeUnit.SECONDS);
        assertEquals(200, response.statusCode());
        assertEquals("finished during shutdown", response.body());
        assertTrue(server.awaitStopped(5, TimeUnit.SECONDS));
        assertEquals(0, server.getActiveRequests());
    }

    @Test
    void shutdownTimeoutInterruptsRequestsThatTakeTooLong() throws Exception {
        HttpServer quick = new HttpServer();
        quick.setShutdownTimeout(Duration.ofMillis(200));
        CountDownLatch started = new CountDownLatch(1);
        quick.get("/forever", (req, resp) -> {
            started.countDown();
            Thread.sleep(60_000);
            return "never";
        });
        Thread thread = new Thread(() -> {
            try {
                quick.start(0);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        thread.start();
        assertTrue(quick.awaitStarted(5, TimeUnit.SECONDS));
        int quickPort = quick.getPort();
        CompletableFuture.runAsync(() -> {
            try (Socket socket = new Socket("127.0.0.1", quickPort)) {
                socket.getOutputStream().write("GET /forever HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                socket.getInputStream().readAllBytes();
            } catch (IOException ignored) {
                // the connection is cut when the worker is interrupted
            }
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));

        long begin = System.nanoTime();
        quick.stop();
        assertTrue(quick.awaitStopped(5, TimeUnit.SECONDS));

        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - begin) < 3);
        thread.join(5_000);
        assertFalse(thread.isAlive());
    }

    @Test
    void aServerInstanceCannotBeStartedTwice() {
        assertThrows(IllegalStateException.class, () -> server.start(0));
    }

    // --------------------------------------------------------------------- helpers

    private URI uri(String pathAndQuery) {
        return URI.create("http://127.0.0.1:" + port + pathAndQuery);
    }

    private HttpResponse<String> get(String pathAndQuery) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(pathAndQuery)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<byte[]> getBytes(String pathAndQuery) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(pathAndQuery)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    /** Sends raw bytes (to simulate broken clients) and returns whatever the server answers. */
    private String raw(String request) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(3_000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
