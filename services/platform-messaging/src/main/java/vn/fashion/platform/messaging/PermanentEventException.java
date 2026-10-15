package vn.fashion.platform.messaging;

public class PermanentEventException extends RuntimeException {
  public PermanentEventException(String message) { super(message); }
  public PermanentEventException(String message, Throwable cause) { super(message, cause); }
}
