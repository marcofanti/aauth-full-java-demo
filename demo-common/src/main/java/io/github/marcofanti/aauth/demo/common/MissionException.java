package io.github.marcofanti.aauth.demo.common;

/** Failure talking to the Person Server's mission layer, or a denied/expired mission request. */
public class MissionException extends RuntimeException {

    public MissionException(String message) {
        super(message);
    }

    public MissionException(String message, Throwable cause) {
        super(message, cause);
    }
}
