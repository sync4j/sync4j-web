package com.fathzer.sync4j.web.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Slows down the attempts to guess a password (brute force).
 * <br>After {@value #FREE_ATTEMPTS} failed logins of a user, the next ones are refused for a delay that doubles at each failure
 * (1 s, 2 s, 4 s, ... up to {@link #MAX_DELAY}). A successful login forgets the failures, and so does an hour without failure.
 * <br>The failures are counted by user name, not by address: behind a reverse proxy, all the requests come from the address of the proxy.
 */
final class LoginThrottle {
    /** The number of failures before the logins of a user are delayed. */
    static final int FREE_ATTEMPTS = 3;
    /** The first delay. */
    static final Duration FIRST_DELAY = Duration.ofSeconds(1);
    /** The longest delay. */
    static final Duration MAX_DELAY = Duration.ofMinutes(5);
    /** The failures of a user are forgotten after this time without a new one. */
    static final Duration FORGET_AFTER = Duration.ofHours(1);

    private record Failures(int count, Instant blockedUntil, Instant last) {}

    private final Map<String, Failures> failures = new ConcurrentHashMap<>();
    private final Clock clock;

    LoginThrottle(Clock clock) {
        this.clock = clock;
    }

    /**
     * Gets how long a user has to wait before trying to log in again.
     * @param user the user name
     * @return the delay, zero if the user can try now
     */
    Duration remaining(String user) {
        final Failures current = failures.get(key(user));
        if (current == null) {
            return Duration.ZERO;
        }
        final Duration result = Duration.between(clock.instant(), current.blockedUntil());
        return result.isNegative() ? Duration.ZERO : result;
    }

    /**
     * Records a failed login.
     * @param user the user name
     */
    void failed(String user) {
        final Instant now = clock.instant();
        // The old failures are forgotten, so the memory does not grow forever
        failures.values().removeIf(f -> f.last().plus(FORGET_AFTER).isBefore(now));
        failures.compute(key(user), (k, previous) -> {
            final int count = previous == null ? 1 : previous.count() + 1;
            return new Failures(count, now.plus(delay(count)), now);
        });
    }

    /**
     * Records a successful login: the failures of the user are forgotten.
     * @param user the user name
     */
    void succeeded(String user) {
        failures.remove(key(user));
    }

    /** The delay after a number of failures. */
    static Duration delay(int failures) {
        if (failures < FREE_ATTEMPTS) {
            return Duration.ZERO;
        }
        // The exponent is limited, so the delay does not overflow
        final Duration delay = FIRST_DELAY.multipliedBy(1L << Math.min(failures - FREE_ATTEMPTS, 20));
        return delay.compareTo(MAX_DELAY) > 0 ? MAX_DELAY : delay;
    }

    private static String key(String user) {
        // "Alice" and "alice " are the same user for most authentication services
        return user.trim().toLowerCase(Locale.ROOT);
    }
}
