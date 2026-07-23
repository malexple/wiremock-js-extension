package ru.mcs.wiremockjs.exception;

public class ScriptTooLargeException extends RuntimeException {
    public ScriptTooLargeException(String message) {
        super(message);
    }
}