package co.edu.escuelaing.webframework;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps an HTTP method and an exact path to a {@link RouteHandler}.
 *
 * <p>The router knows nothing about sockets or files: it is only a lookup table.
 * That is what lets new routes be registered without touching the server loop.
 */
public final class Router {

    private final Map<String, RouteHandler> routes = new ConcurrentHashMap<>();

    /**
     * Registers a handler.
     *
     * @throws IllegalArgumentException if the path does not start with '/'
     * @throws IllegalStateException    if the same method+path was already registered
     */
    public void register(String method, String path, RouteHandler handler) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(handler, "handler");
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("Route path must start with '/': " + path);
        }
        if (routes.putIfAbsent(key(method, path), handler) != null) {
            throw new IllegalStateException("Route already registered: " + method + " " + path);
        }
    }

    /** Finds the handler for an exact method and path. */
    public Optional<RouteHandler> find(String method, String path) {
        return Optional.ofNullable(routes.get(key(method, path)));
    }

    public int size() {
        return routes.size();
    }

    private static String key(String method, String path) {
        return method + " " + path;
    }
}
