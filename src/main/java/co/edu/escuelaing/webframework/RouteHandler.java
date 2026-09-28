package co.edu.escuelaing.webframework;

/**
 * A service registered with {@link WebFramework#get(String, RouteHandler)}.
 *
 * <p>It is a functional interface, so application developers write it as a lambda:
 * <pre>{@code get("/pi", (req, resp) -> String.valueOf(Math.PI));}</pre>
 *
 * <p>The returned {@code String} becomes the HTTP response body. A handler may also
 * use the {@link Response} to change the status code, the content type or add headers.
 * Returning {@code null} means "keep whatever body the response already has".
 */
@FunctionalInterface
public interface RouteHandler {

    String handle(Request request, Response response) throws Exception;
}
