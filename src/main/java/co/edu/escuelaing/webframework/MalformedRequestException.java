package co.edu.escuelaing.webframework;

/**
 * Thrown when the bytes received from a client cannot be interpreted as a valid
 * HTTP request. The server translates it into a {@code 400 Bad Request} response
 * instead of failing.
 */
public class MalformedRequestException extends Exception {

    private static final long serialVersionUID = 1L;

    public MalformedRequestException(String message) {
        super(message);
    }

    public MalformedRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
