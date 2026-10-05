package com.undertalemodtool.avalonia.android.api.client;

/**
 * Anything the API reported as a failure: a refused bind, a missing permission, an unknown job, a
 * script compile error, a data file that could not be read, the tool's process going away.
 *
 * <p>The message is the {@code error} string of the JSON document the service returned, so it is
 * the text the tool's own log would show.
 */
public class UmApiException extends Exception {

    private static final long serialVersionUID = 1L;

    public UmApiException(String message) {
        super(message);
    }

    public UmApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
