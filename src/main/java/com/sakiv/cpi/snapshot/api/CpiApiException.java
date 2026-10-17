package com.sakiv.cpi.snapshot.api;

import java.io.IOException;

public class CpiApiException extends IOException {

    private final int status;

    public CpiApiException(String message) {
        this(message, -1);
    }

    public CpiApiException(String message, int status) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
