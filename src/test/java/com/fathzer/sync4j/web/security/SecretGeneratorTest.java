package com.fathzer.sync4j.web.security;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class SecretGeneratorTest {
    @Test
    void testGeneratedSecret() {
        final String secret = SecretGenerator.generate();

        // 64 random bytes in base64, on a single line
        assertEquals(SecretGenerator.SECRET_LENGTH, Base64.getDecoder().decode(secret).length);
        assertFalse(secret.contains("\n"));
        // It is a valid secret for the login (and it can sign tickets)
        assertDoesNotThrow(() -> new JwtLoginManager((user, password) -> user, secret, true));
    }

    @Test
    void testSecretsAreDifferent() {
        final Set<String> secrets = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            secrets.add(SecretGenerator.generate());
        }
        assertEquals(50, secrets.size());
    }

    @Test
    void testBuildSecretUsesTheGenerator() {
        assertEquals(SecretGenerator.SECRET_LENGTH, Base64.getDecoder().decode(JwtLoginManager.buildSecret()).length);
    }

    @Test
    void testMainPrintsTheSecretAfterAPrefix() {
        final PrintStream out = System.out;
        final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            SecretGenerator.main(new String[0]);
        } finally {
            System.setOut(out);
        }

        final String output = captured.toString(StandardCharsets.UTF_8);
        assertTrue(output.endsWith(System.lineSeparator()));
        assertTrue(output.startsWith("Secret: "), output);
        assertEquals(SecretGenerator.SECRET_LENGTH, Base64.getDecoder().decode(output.trim().substring("Secret: ".length())).length);
    }

    @Test
    void testRunWithoutAnyLibrary() throws IOException, InterruptedException, URISyntaxException {
        // The class only needs the JDK: it must work with nothing else than its own classes on the classpath
        final String classes = Paths.get(SecretGenerator.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        final String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        final Process process = new ProcessBuilder(java, "-cp", classes, SecretGenerator.class.getName()).redirectErrorStream(true).start();

        final String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.startsWith("Secret: "), output);
        assertEquals(SecretGenerator.SECRET_LENGTH, Base64.getDecoder().decode(output.trim().substring("Secret: ".length())).length, output);
    }
}
