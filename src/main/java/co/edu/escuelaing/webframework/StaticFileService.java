package co.edu.escuelaing.webframework;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Serves static resources (HTML, CSS, JavaScript, images...) from a location that
 * is either a folder on the classpath ({@code /webroot}) or a directory on disk.
 *
 * <p>Files are read as raw bytes, so binary resources such as PNG images work exactly
 * like text files. The service never throws for a missing file: it returns an empty
 * {@link Optional} and lets the server answer {@code 404}.
 */
public final class StaticFileService {

    /** Where the bytes come from. Returns {@code null} when the resource does not exist. */
    @FunctionalInterface
    interface ResourceSource {
        byte[] read(String relativePath) throws IOException;
    }

    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=UTF-8"),
            Map.entry("htm", "text/html; charset=UTF-8"),
            Map.entry("css", "text/css; charset=UTF-8"),
            Map.entry("js", "text/javascript; charset=UTF-8"),
            Map.entry("json", "application/json; charset=UTF-8"),
            Map.entry("txt", "text/plain; charset=UTF-8"),
            Map.entry("xml", "application/xml; charset=UTF-8"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("pdf", "application/pdf"));

    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private final ResourceSource source;
    private final String description;

    private StaticFileService(ResourceSource source, String description) {
        this.source = source;
        this.description = description;
    }

    /** Serves files packaged inside the application (e.g. {@code src/main/resources/webroot}). */
    public static StaticFileService fromClasspath(String folder) {
        String prefix = normalizeFolder(folder);
        return new StaticFileService(path -> readFromClasspath(prefix + path), "classpath:" + prefix);
    }

    /** Serves files from a directory on disk (used with the {@code STATIC_FILES_PATH} variable). */
    public static StaticFileService fromDirectory(Path directory) {
        Path root = directory.toAbsolutePath().normalize();
        return new StaticFileService(path -> {
            Path file = root.resolve(path.substring(1)).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                return null;
            }
            return Files.readAllBytes(file);
        }, "directory:" + root);
    }

    /**
     * Looks for the resource that corresponds to a request path.
     *
     * @param requestPath decoded path such as {@code /images/logo.png}; {@code /} maps to {@code /index.html}
     * @return the response, or empty if the resource does not exist or the path is unsafe
     * @throws IOException if the resource exists but cannot be read
     */
    public Optional<Response> serve(String requestPath) throws IOException {
        String path = requestPath.endsWith("/") ? requestPath + "index.html" : requestPath;
        if (!isSafe(path)) {
            return Optional.empty();
        }
        byte[] content = source.read(path);
        if (content == null) {
            return Optional.empty();
        }
        return Optional.of(Response.of(200, contentTypeFor(path), content));
    }

    /** Rejects directory-traversal attempts ({@code ..}), backslashes and NUL bytes. */
    static boolean isSafe(String path) {
        if (!path.startsWith("/") || path.indexOf('\0') >= 0 || path.indexOf('\\') >= 0) {
            return false;
        }
        for (String segment : path.split("/")) {
            if (segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    static String contentTypeFor(String path) {
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) {
            return DEFAULT_CONTENT_TYPE;
        }
        String extension = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return CONTENT_TYPES.getOrDefault(extension, DEFAULT_CONTENT_TYPE);
    }

    private static String normalizeFolder(String folder) {
        String value = folder.trim();
        if (!value.startsWith("/")) {
            value = "/" + value;
        }
        while (value.length() > 1 && value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static byte[] readFromClasspath(String resourceName) throws IOException {
        URL url = StaticFileService.class.getResource(resourceName);
        if (url == null) {
            return null;
        }
        URLConnection connection = url.openConnection();
        // A folder is not a file: without this check /images would return a listing or empty 200.
        if (connection instanceof JarURLConnection jar && jar.getJarEntry().isDirectory()) {
            return null;
        }
        if ("file".equals(url.getProtocol())) {
            try {
                if (Files.isDirectory(Path.of(url.toURI()))) {
                    return null;
                }
            } catch (URISyntaxException e) {
                return null;
            }
        }
        try (InputStream in = connection.getInputStream()) {
            return in.readAllBytes();
        }
    }

    @Override
    public String toString() {
        return description;
    }
}
