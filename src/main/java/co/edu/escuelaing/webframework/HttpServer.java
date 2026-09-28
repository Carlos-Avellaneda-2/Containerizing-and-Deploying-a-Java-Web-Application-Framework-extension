package co.edu.escuelaing.webframework;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The HTTP infrastructure: accepts TCP connections, parses requests, asks the
 * {@link Router} for a dynamic handler, falls back to the {@link StaticFileService}
 * and writes the response.
 *
 * <p><b>The server is concurrent</b>: the accept loop only accepts connections and hands
 * each one to a bounded pool of worker threads, so a slow request no longer blocks the
 * others. The loop below never mentions a concrete route; adding a service means calling
 * {@link #get(String, RouteHandler)}, not editing this class.
 *
 * <p><b>Shutdown is graceful</b>: {@link #stop()} closes the listening socket (no new
 * connections are accepted), then the requests already in progress are allowed to finish
 * for up to {@link #setShutdownTimeout(Duration) the shutdown timeout} before the workers
 * are interrupted.
 */
public final class HttpServer {

    static final String DEFAULT_STATIC_FOLDER = "/webroot";
    static final int DEFAULT_WORKER_THREADS = 16;
    static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);

    private static final int MAX_LINE_BYTES = 8 * 1024;
    private static final int MAX_HEADERS = 100;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Router router = new Router();
    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final AtomicInteger activeRequests = new AtomicInteger();

    private volatile StaticFileService staticFiles = StaticFileService.fromClasspath(DEFAULT_STATIC_FOLDER);
    private volatile boolean running;
    private volatile ServerSocket serverSocket;
    private volatile int port = -1;
    private volatile int workerThreads = DEFAULT_WORKER_THREADS;
    private volatile Duration shutdownTimeout = DEFAULT_SHUTDOWN_TIMEOUT;

    /**
     * How long a worker waits for a client to send its request. Without this limit a
     * client that connects and stays silent (browsers do this to pre-connect) would keep
     * a worker thread busy forever.
     */
    private volatile int clientTimeoutMillis = 5_000;

    // ------------------------------------------------------------------ registration

    /** Registers a dynamic GET service. */
    public void get(String path, RouteHandler handler) {
        router.register("GET", path, handler);
    }

    /** Serves static files packaged in the classpath folder, e.g. {@code "/webroot"}. */
    public void staticfiles(String classpathFolder) {
        this.staticFiles = StaticFileService.fromClasspath(classpathFolder);
    }

    /** Serves static files from a directory on disk. */
    public void staticfiles(Path directory) {
        this.staticFiles = StaticFileService.fromDirectory(directory);
    }

    // -------------------------------------------------------------------- life cycle

    /**
     * Binds to all network interfaces on {@code port} (0 = any free port) and serves
     * requests concurrently until {@link #stop()} is called. Blocks the calling thread
     * until the server has fully stopped, including the requests that were in progress.
     */
    public void start(int port) throws IOException {
        if (running || started.getCount() == 0) {
            throw new IllegalStateException("A server instance can only be started once");
        }
        ExecutorService workers = Executors.newFixedThreadPool(workerThreads, workerThreadFactory());
        // new ServerSocket(port) binds to the wildcard address (0.0.0.0), NOT only to
        // localhost, so a cloud platform / Docker port mapping can reach it.
        try (ServerSocket socket = new ServerSocket(port)) {
            this.serverSocket = socket;
            this.port = socket.getLocalPort();
            this.running = true;
            started.countDown();
            log("Server listening on 0.0.0.0:" + this.port + " with " + workerThreads + " worker threads");

            while (running) {
                Socket client;
                try {
                    client = socket.accept();
                } catch (IOException e) {
                    if (running) {
                        log("Accept error: " + e.getMessage());
                    }
                    if (socket.isClosed()) {
                        break;
                    }
                    continue;
                }
                try {
                    workers.execute(() -> serve(client));
                } catch (RejectedExecutionException e) {
                    closeQuietly(client); // only happens while shutting down
                }
            }
        } finally {
            running = false;
            serverSocket = null;
            drain(workers);
            stopped.countDown();
        }
        log("Server stopped gracefully.");
    }

    /**
     * Requests a graceful stop and returns immediately. The listening socket is closed so
     * no new connection is accepted; requests already in progress (including the one that
     * called {@code stop()}, e.g. {@code /shutdown}) finish and send their responses.
     * Use {@link #awaitStopped(long, TimeUnit)} to wait for the end of the shutdown.
     */
    public void stop() {
        running = false;
        closeQuietly(serverSocket);
    }

    /**
     * Stops the server and waits (up to the shutdown timeout plus a small margin) until
     * the in-flight requests have completed. Intended for JVM shutdown hooks, so that
     * {@code docker stop} / SIGTERM does not cut requests in half.
     */
    public void stopAndWait() {
        stop();
        if (started.getCount() == 0) {
            try {
                awaitStopped(shutdownTimeout.toMillis() + 2_000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public boolean isRunning() {
        return running;
    }

    /** The port actually bound (useful when started with port 0), or -1 if not started. */
    public int getPort() {
        return port;
    }

    /** Number of requests being processed right now. */
    public int getActiveRequests() {
        return activeRequests.get();
    }

    /** Waits until the server socket is bound. Mostly useful for tests. */
    public boolean awaitStarted(long timeout, TimeUnit unit) throws InterruptedException {
        return started.await(timeout, unit);
    }

    /** Waits until the server has stopped and every in-flight request has finished. */
    public boolean awaitStopped(long timeout, TimeUnit unit) throws InterruptedException {
        return stopped.await(timeout, unit);
    }

    /** Size of the worker pool. Must be called before {@link #start(int)}. */
    public void setWorkerThreads(int threads) {
        if (threads < 1) {
            throw new IllegalArgumentException("Worker threads must be at least 1");
        }
        this.workerThreads = threads;
    }

    /** Maximum time {@link #stop()} lets in-flight requests run before interrupting them. */
    public void setShutdownTimeout(Duration timeout) {
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("Shutdown timeout cannot be negative");
        }
        this.shutdownTimeout = timeout;
    }

    void setClientTimeoutMillis(int millis) {
        this.clientTimeoutMillis = millis;
    }

    /** Lets in-flight requests finish, then interrupts whatever is still running. */
    private void drain(ExecutorService workers) {
        workers.shutdown();
        int inFlight = activeRequests.get();
        if (inFlight > 0) {
            log("Waiting for " + inFlight + " in-flight request(s) to finish...");
        }
        try {
            if (!workers.awaitTermination(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                log("Shutdown timeout reached; interrupting " + activeRequests.get() + " request(s)");
                workers.shutdownNow();
                workers.awaitTermination(1, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static ThreadFactory workerThreadFactory() {
        AtomicInteger counter = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, "http-worker-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // nothing else to do: the resource is being discarded
        }
    }

    // ------------------------------------------------------------- request processing

    /**
     * Turns a parsed request into a response: dynamic route first, then static file,
     * then 404. Public so it can be tested without sockets.
     */
    public Response dispatch(Request request) {
        try {
            if (!"GET".equals(request.getMethod())) {
                Response notAllowed = Response.text(405, "405 Method Not Allowed");
                notAllowed.setHeader("Allow", "GET");
                return notAllowed;
            }

            Optional<RouteHandler> handler = router.find(request.getMethod(), request.getPath());
            if (handler.isPresent()) {
                Response response = new Response();
                String body = handler.get().handle(request, response);
                if (body != null) {
                    response.setBody(body);
                }
                return response;
            }

            Optional<Response> file = staticFiles.serve(request.getPath());
            if (file.isPresent()) {
                return file.get();
            }
            return Response.text(404, "404 Not Found");

        } catch (Exception e) {
            // A failing handler must never take the server down.
            log("Error while handling " + request + ": " + e);
            return Response.text(500, "500 Internal Server Error");
        }
    }

    /** Runs on a worker thread: serves one connection and always closes it. */
    private void serve(Socket client) {
        activeRequests.incrementAndGet();
        try (client) {
            handle(client);
        } catch (IOException e) {
            log("Error while closing connection: " + e.getMessage());
        } finally {
            activeRequests.decrementAndGet();
        }
    }

    private void handle(Socket client) {
        String requestLine = "-";
        try {
            client.setSoTimeout(clientTimeoutMillis);
            InputStream in = new BufferedInputStream(client.getInputStream());

            Response response;
            boolean malformed = false;
            try {
                String line = readLine(in);
                if (line == null) {
                    return; // the client connected and closed without sending anything
                }
                requestLine = line;
                Request request = Request.parse(line);          // fail fast: bad line -> 400 now,
                request = request.withHeaders(readHeaders(in)); // without waiting for headers
                response = dispatch(request);
            } catch (MalformedRequestException e) {
                malformed = true;
                response = Response.text(400, "400 Bad Request: " + e.getMessage());
            }

            OutputStream out = client.getOutputStream();
            out.write(response.toBytes());
            out.flush();
            log("\"" + sanitize(requestLine) + "\" -> " + response.getStatus());

            if (malformed) {
                lingeringClose(client);
            }

        } catch (SocketTimeoutException e) {
            log("Client timed out without completing a request");
        } catch (IOException e) {
            log("I/O error while serving \"" + sanitize(requestLine) + "\": " + e.getMessage());
        }
    }

    /**
     * After rejecting a request we may not have read everything the client sent. Closing
     * a socket with unread input makes TCP send a reset that can destroy the 400 response
     * before the client reads it. So: send FIN, then discard (a bounded amount of) input.
     */
    private static void lingeringClose(Socket client) {
        try {
            client.shutdownOutput();
            client.setSoTimeout(200);
            InputStream in = client.getInputStream();
            byte[] discard = new byte[1024];
            int total = 0;
            int n;
            while (total < 64 * 1024 && (n = in.read(discard)) != -1) {
                total += n;
            }
        } catch (IOException ignored) {
            // the client is gone or too slow: nothing more to do
        }
    }

    /** Reads one CRLF/LF-terminated line, refusing lines longer than the limit. */
    private static String readLine(InputStream in) throws IOException, MalformedRequestException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1 && b != '\n') {
            if (buffer.size() >= MAX_LINE_BYTES) {
                throw new MalformedRequestException("Request line or header too long");
            }
            buffer.write(b);
        }
        if (b == -1 && buffer.size() == 0) {
            return null; // end of stream, nothing read
        }
        String line = buffer.toString(StandardCharsets.UTF_8);
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    /** Reads header lines up to the blank line; names are stored in lower case. */
    private static Map<String, String> readHeaders(InputStream in)
            throws IOException, MalformedRequestException {
        Map<String, String> headers = new HashMap<>();
        for (int count = 0; ; count++) {
            String line = readLine(in);
            if (line == null || line.isEmpty()) {
                return headers;
            }
            if (count >= MAX_HEADERS) {
                throw new MalformedRequestException("Too many headers");
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new MalformedRequestException("Malformed header line");
            }
            headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                    line.substring(colon + 1).trim());
        }
    }

    /** Replaces control characters so a client cannot forge log lines. */
    private static String sanitize(String text) {
        return text.replaceAll("\\p{Cntrl}", "?");
    }

    private static void log(String message) {
        System.out.println("[" + LocalTime.now().format(TIME) + "] [" + Thread.currentThread().getName() + "] " + message);
    }
}
