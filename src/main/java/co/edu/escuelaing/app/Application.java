package co.edu.escuelaing.app;

import static co.edu.escuelaing.webframework.WebFramework.configure;
import static co.edu.escuelaing.webframework.WebFramework.get;
import static co.edu.escuelaing.webframework.WebFramework.server;
import static co.edu.escuelaing.webframework.WebFramework.start;
import static co.edu.escuelaing.webframework.WebFramework.staticfiles;
import static co.edu.escuelaing.webframework.WebFramework.stop;
import static co.edu.escuelaing.webframework.WebFramework.stopOnJvmShutdown;

import co.edu.escuelaing.webframework.Config;
import java.nio.file.Path;

/**
 * Example application built on top of the framework.
 *
 * <p>This class contains only <em>behaviour</em> (which services exist and what they
 * answer). Sockets, HTTP parsing and file serving live in the framework package.
 * Deployment-specific values come from environment variables through {@link Config}.
 */
public final class Application {

    static final long MAX_SLOW_MILLIS = 10_000;

    private Application() {
    }

    public static void main(String[] args) throws Exception {
        Config config = Config.fromEnvironment();
        int port = config.getPort(); // fail fast if PORT, WORKER_THREADS... are invalid
        configure(config);

        registerRoutes(config);
        stopOnJvmShutdown(); // docker stop (SIGTERM) -> finish in-flight requests, then exit

        System.out.println("APP_ENV=" + config.getAppEnv()
                + " | workers=" + config.getWorkerThreads()
                + " | shutdown timeout=" + config.getShutdownTimeout().toSeconds() + "s"
                + " | shutdown route " + (config.isDevelopment() ? "ENABLED" : "DISABLED"));

        start(port);
    }

    /** Declares static files and every dynamic service of the example application. */
    static void registerRoutes(Config config) {

        // Static resources: an external folder if STATIC_FILES_PATH is set, otherwise /webroot.
        config.get(Config.STATIC_FILES_PATH).ifPresentOrElse(
                folder -> staticfiles(Path.of(folder)),
                () -> staticfiles("/webroot"));

        // GET /hello?name=Pedro  ->  "Hello Pedro"   (prefix comes from GREETING_PREFIX)
        get("/hello", (req, resp) -> {
            String name = req.getValue("name");
            if (name == null || name.isBlank()) {
                name = "world";
            }
            String greetingPrefix = config.get("GREETING_PREFIX", "Hello");
            return greetingPrefix + " " + name;
        });

        // GET /pi
        get("/pi", (req, resp) -> String.valueOf(Math.PI));

        // GET /add?a=2&b=3  ->  "5"   (shows several query parameters and error statuses)
        get("/add", (req, resp) -> {
            String a = req.getValue("a");
            String b = req.getValue("b");
            if (a == null || b == null) {
                resp.setStatus(400);
                return "Missing parameters. Usage: /add?a=2&b=3";
            }
            try {
                return String.valueOf(Math.addExact(Long.parseLong(a), Long.parseLong(b)));
            } catch (NumberFormatException | ArithmeticException e) {
                resp.setStatus(400);
                return "Parameters 'a' and 'b' must be whole numbers that fit in 64 bits";
            }
        });

        // GET /config -> non-sensitive deployment settings (evidence for the README).
        get("/config", (req, resp) -> {
            resp.setContentType("application/json; charset=UTF-8");
            return "{\"appEnv\":\"" + jsonEscape(config.getAppEnv()) + "\","
                    + "\"greetingPrefix\":\"" + jsonEscape(config.get("GREETING_PREFIX", "Hello")) + "\","
                    + "\"port\":" + config.getPort() + ","
                    + "\"workerThreads\":" + config.getWorkerThreads() + ","
                    + "\"shutdownTimeoutSeconds\":" + config.getShutdownTimeout().toSeconds() + ","
                    + "\"shutdownEnabled\":" + config.isDevelopment() + "}";
        });

        // GET /slow?ms=2000 -> answers after a delay. Used to show that several slow requests
        // are served in parallel and that a shutdown waits for them to finish.
        get("/slow", (req, resp) -> {
            long millis;
            try {
                String value = req.getValue("ms");
                millis = value == null ? 2_000 : Long.parseLong(value);
            } catch (NumberFormatException e) {
                millis = -1;
            }
            if (millis < 0 || millis > MAX_SLOW_MILLIS) {
                resp.setStatus(400);
                return "Parameter 'ms' must be a whole number between 0 and " + MAX_SLOW_MILLIS;
            }
            Thread.sleep(millis);
            return "Done after " + millis + " ms on " + Thread.currentThread().getName();
        });

        // GET /status -> worker that served the request and requests currently in progress.
        get("/status", (req, resp) -> {
            resp.setContentType("application/json; charset=UTF-8");
            return "{\"thread\":\"" + jsonEscape(Thread.currentThread().getName()) + "\","
                    + "\"activeRequests\":" + server().getActiveRequests() + "}";
        });

        // GET /shutdown -> only registered in development. In production the route does
        // not exist, so the request falls through to the static-file lookup and returns 404.
        if (config.isDevelopment()) {
            get("/shutdown", (req, resp) -> {
                stop();
                return "Server will stop after this response.";
            });
        }
    }

    private static String jsonEscape(String value) {
        StringBuilder out = new StringBuilder();
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
