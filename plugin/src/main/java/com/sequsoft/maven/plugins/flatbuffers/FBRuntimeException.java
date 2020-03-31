package com.sequsoft.maven.plugins.flatbuffers;

public class FBRuntimeException extends RuntimeException {
    public FBRuntimeException() {
    }

    public FBRuntimeException(String message) {
        super(message);
    }

    public FBRuntimeException(String message, Throwable cause) {
        super(message, cause);
    }

    public FBRuntimeException(Throwable cause) {
        super(cause);
    }

    public FBRuntimeException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
