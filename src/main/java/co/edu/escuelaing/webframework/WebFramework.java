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

    /** Starts the server on the port given by the {@code PORT} variable (default 8080). */
    public static void start() throws IOException {
        start(Config.fromEnvironment().getPort());
    }

    /** Starts the server on an explicit port. Blocks until {@link #stop()} is called. */
    public static void start(int port) throws IOException {
        server.start(port);
    }

    /** Stops the server gracefully after the current request completes. */
    public static void stop() {
        server.stop();
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
