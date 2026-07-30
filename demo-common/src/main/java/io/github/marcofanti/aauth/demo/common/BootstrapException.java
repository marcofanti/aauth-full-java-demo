package io.github.marcofanti.aauth.demo.common;

/** Agent bootstrap against the Person Server failed; the service should not start. */
public class BootstrapException extends RuntimeException {

    public BootstrapException(String message) {
        super(message);
    }

    public BootstrapException(String message, Throwable cause) {
        super(message, cause);
    }
}
