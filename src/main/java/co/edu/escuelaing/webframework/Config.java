package co.edu.escuelaing.webframework;

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

    public static final int DEFAULT_PORT = 8080;
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
        Optional<String> value = get(PORT);
        if (value.isEmpty()) {
            return DEFAULT_PORT;
        }
        try {
            int port = Integer.parseInt(value.get());
            if (port >= 1 && port <= 65535) {
                return port;
            }
        } catch (NumberFormatException ignored) {
            // falls through to the error below
        }
        throw new IllegalArgumentException(
                "PORT must be an integer between 1 and 65535, but was '" + value.get() + "'");
    }

    /** The execution environment name from {@code APP_ENV} (default {@code development}). */
    public String getAppEnv() {
        return get(APP_ENV, DEFAULT_APP_ENV);
    }

    public boolean isDevelopment() {
        return DEFAULT_APP_ENV.equalsIgnoreCase(getAppEnv());
    }
}
