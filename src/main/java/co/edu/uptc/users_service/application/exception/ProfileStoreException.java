package co.edu.uptc.users_service.application.exception;

/** Persistence failure exposed without leaking framework-specific exception types. */
public class ProfileStoreException extends RuntimeException {
    public ProfileStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
