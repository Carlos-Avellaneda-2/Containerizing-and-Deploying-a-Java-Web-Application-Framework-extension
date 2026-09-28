package co.edu.escuelaing.webframework;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Read-only view of the deployment configuration (environment variables).
 *
 * <p>Nothing deployment-specific is hard-coded: the port, the environment name and
 * any other setting come from the environment. The source is injectable
 * ({@code new Config(map)}) so tests can simulate any environment.
 */
public final class Config {

    public static final String PORT = "PORT";
    public static final String APP_ENV = "APP_ENV";
    public static final String STATIC_FILES_PATH = "STATIC_FILES_PATH";
    public static final String WORKER_THREADS = "WORKER_THREADS";
    public static final String SHUTDOWN_TIMEOUT_SECONDS = "SHUTDOWN_TIMEOUT_SECONDS";

    public static final int DEFAULT_PORT = 8080;
    public static final int DEFAULT_WORKER_THREADS = 16;
    public static final int DEFAULT_SHUTDOWN_TIMEOUT_SECONDS = 10;
    public static final String DEFAULT_APP_ENV = "development";

    private final Map<String, String> env;

    public Config(Map<String, String> env) {
        this.env = Map.copyOf(env);
    }

    /** Configuration backed by the real process environment. */
    public static Config fromEnvironment() {
        return new Config(System.getenv());
    }

    /** Returns the variable, or {@code defaultValue} when it is unset or blank. */
    public String get(String name, String defaultValue) {
        return get(name).orElse(defaultValue);
    }

    /** Returns the trimmed variable, or empty when it is unset or blank. */
    public Optional<String> get(String name) {
        String value = env.get(name);
        return (value == null || value.isBlank()) ? Optional.empty() : Optional.of(value.trim());
    }

    /**
     * The TCP port from {@code PORT}, or {@value #DEFAULT_PORT} when unset.
     *
     * @throws IllegalArgumentException if PORT is not an integer between 1 and 65535
     */
    public int getPort() {
        return getInt(PORT, DEFAULT_PORT, 1, 65535);
    }

    /** Size of the worker pool from {@code WORKER_THREADS} (default {@value #DEFAULT_WORKER_THREADS}). */
    public int getWorkerThreads() {
        return getInt(WORKER_THREADS, DEFAULT_WORKER_THREADS, 1, 1024);
    }

    /**
     * Seconds that in-flight requests get to finish during shutdown, from
     * {@code SHUTDOWN_TIMEOUT_SECONDS} (default {@value #DEFAULT_SHUTDOWN_TIMEOUT_SECONDS}).
     */
    public Duration getShutdownTimeout() {
        return Duration.ofSeconds(getInt(SHUTDOWN_TIMEOUT_SECONDS, DEFAULT_SHUTDOWN_TIMEOUT_SECONDS, 0, 3600));
    }

    /**
     * Reads an integer variable in {@code [min, max]}, or {@code defaultValue} when unset.
     *
     * @throws IllegalArgumentException if the value is not an integer in range
     */
    private int getInt(String name, int defaultValue, int min, int max) {
        Optional<String> value = get(name);
        if (value.isEmpty()) {
            return defaultValue;
        }
        try {
            int number = Integer.parseInt(value.get());
            if (number >= min && number <= max) {
                return number;
            }
        } catch (NumberFormatException ignored) {
            // falls through to the error below
        }
        throw new IllegalArgumentException(
                name + " must be an integer between " + min + " and " + max + ", but was '" + value.get() + "'");
    }

    /** The execution environment name from {@code APP_ENV} (default {@code development}). */
    public String getAppEnv() {
        return get(APP_ENV, DEFAULT_APP_ENV);
    }

    public boolean isDevelopment() {
        return DEFAULT_APP_ENV.equalsIgnoreCase(getAppEnv());
    }
}
