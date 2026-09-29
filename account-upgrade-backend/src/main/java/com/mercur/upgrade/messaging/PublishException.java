package com.mercur.upgrade.messaging;

/** Raised when an event cannot be handed over to the broker. */
public class PublishException extends RuntimeException {

    public PublishException(String message) {
        super(message);
    }

    public PublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
