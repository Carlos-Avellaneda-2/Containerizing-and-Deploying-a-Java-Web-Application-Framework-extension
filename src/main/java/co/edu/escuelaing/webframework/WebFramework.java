package co.edu.escuelaing.webframework;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The public face of the framework. Application code uses it through a static import:
 *
 * <pre>{@code
 * import static co.edu.escuelaing.webframework.WebFramework.*;
 *
 * staticfiles("/webroot");
 * get("/hello", (req, resp) -> "Hello " + req.getValue("name"));
 * start();
 * }</pre>
 *
 * <p>It only delegates to a shared {@link HttpServer}; sockets, parsing and routing
 * stay hidden behind {@code get()}, {@code staticfiles()}, {@code start()} and {@code stop()}.
 */
public final class WebFramework {

    private static volatile HttpServer server = new HttpServer();

    private WebFramework() {
    }

    /** Registers a dynamic GET endpoint implemented by a lambda. */
    public static void get(String path, RouteHandler handler) {
        server.get(path, handler);
    }

    /** Sets the classpath folder that holds the static resources, e.g. {@code "/webroot"}. */
    public static void staticfiles(String classpathFolder) {
        server.staticfiles(classpathFolder);
    }

    /** Sets a directory on disk as the static-resource folder. */
    public static void staticfiles(Path directory) {
        server.staticfiles(directory);
    }

    /**
     * Starts the server configured from the environment: {@code PORT} (default 8080),
     * {@code WORKER_THREADS} and {@code SHUTDOWN_TIMEOUT_SECONDS}.
     */
    public static void start() throws IOException {
        Config config = Config.fromEnvironment();
        configure(config);
        start(config.getPort());
    }

    /** Applies the concurrency and shutdown settings of {@code config}. Call before starting. */
    public static void configure(Config config) {
        server.setWorkerThreads(config.getWorkerThreads());
        server.setShutdownTimeout(config.getShutdownTimeout());
    }

    /** Starts the server on an explicit port. Blocks until the server has fully stopped. */
    public static void start(int port) throws IOException {
        server.start(port);
    }

    /**
     * Stops the server gracefully: no new connections are accepted and the requests in
     * progress are allowed to finish. Returns immediately.
     */
    public static void stop() {
        server.stop();
    }

    /**
     * Makes SIGTERM / Ctrl+C (e.g. {@code docker stop}) trigger a graceful shutdown: the JVM
     * waits for the in-flight requests before exiting.
     */
    public static void stopOnJvmShutdown() {
        HttpServer current = server;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (current.isRunning()) {
                System.out.println("Shutdown signal received: stopping gracefully...");
                current.stopAndWait();
            }
        }, "graceful-shutdown"));
    }

    /** The underlying server, for advanced use and tests. */
    public static HttpServer server() {
        return server;
    }

    /** Discards all registered routes and state. Intended for tests. */
    public static void reset() {
        server = new HttpServer();
    }
}
