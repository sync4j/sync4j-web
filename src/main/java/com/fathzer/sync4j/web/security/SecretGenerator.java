package com.fathzer.sync4j.web.security;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Generates the secret that signs the login tickets (see {@link JwtLoginManager}).
 * <br>This class only needs the JDK, so it can be run with the jar of this library alone:
 * <pre>
 * java -jar sync4j-web-0.0.1.jar
 * </pre>
 * It prints <code>Secret: </code> followed by the secret.
 */
public final class SecretGenerator {
    /** The length of the secrets, in bytes (512 bits, the size of the key of the HS512 signature algorithm). */
    static final int SECRET_LENGTH = 64;

    /** What is printed before the secret. */
    public static final String PREFIX = "Secret: ";

    private static final SecureRandom RANDOM = new SecureRandom();

    private SecretGenerator() {
        // Utility class
    }

    /**
     * Generates a secret.
     * @return random bytes encoded in base64
     */
    public static String generate() {
        final byte[] secret = new byte[SECRET_LENGTH];
        RANDOM.nextBytes(secret);
        return Base64.getEncoder().encodeToString(secret);
    }

    /**
     * Prints a new secret, preceded by {@link #PREFIX}.
     * @param args not used
     */
    public static void main(String[] args) {
        // The secret is the output of this command line: it is printed, not logged
        System.out.println(PREFIX + generate()); // NOSONAR java:S106
    }
}
