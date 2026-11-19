package com.rethinkj.updater;

import java.io.IOException;
import java.io.Serial;
import java.time.Instant;
import java.util.Optional;

/** A source refused a request because too many were made; asking again later will work. */
public final class RateLimitException extends IOException {

  @Serial
  private static final long serialVersionUID = 1L;

  private final transient Instant retryAt;

  public RateLimitException(String message, Instant retryAt) {
    super(message);
    this.retryAt = retryAt;
  }

  /** When the source accepts requests again, if it said so. */
  public Optional<Instant> retryAt() {
    return Optional.ofNullable(retryAt);
  }
}
