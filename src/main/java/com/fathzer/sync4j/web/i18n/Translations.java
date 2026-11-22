package com.fathzer.sync4j.web.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Loads the translations of an application or of a plugin.
 * <br>The translations of a language are in a UTF-8 <code>.properties</code> file named <code>base_language.properties</code> (for instance
 * <code>my-plugin/messages_fr.properties</code>). The English file is required: the server completes the other languages with it.
 */
public final class Translations {
    private static final Pattern LANGUAGE = Pattern.compile("[a-z]{2,3}");

    private Translations() {
        // Utility class
    }

    /**
     * Tests if a language is syntactically valid (two or three lowercase letters).
     * @param language the language
     * @return true if it is
     */
    static boolean isLanguage(String language) {
        return language != null && LANGUAGE.matcher(language).matches();
    }

    /**
     * Loads the translations of a language.
     * @param loader the class loader that finds the resource (usually the one of the plugin)
     * @param base the base name of the resource, without the language and the extension (for instance <code>my-plugin/messages</code>)
     * @param language the language (for instance <code>fr</code>)
     * @return the translations, an empty map if there is no file for this language
     * @throws UncheckedIOException if the file can't be read
     */
    public static Map<String, String> load(ClassLoader loader, String base, String language) {
        if (!isLanguage(language)) {
            return Map.of();
        }
        try (InputStream in = loader.getResourceAsStream(base + "_" + language + ".properties")) {
            if (in == null) {
                return Map.of();
            }
            final Properties properties = new Properties();
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            final Map<String, String> result = new HashMap<>();
            properties.forEach((key, value) -> result.put(key.toString(), value.toString()));
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Tests if there is a file of translations for a language.
     * @param loader the class loader
     * @param base the base name of the resource
     * @param language the language
     * @return true if there is one
     */
    static boolean exists(ClassLoader loader, String base, String language) {
        return isLanguage(language) && loader.getResource(base + "_" + language + ".properties") != null;
    }
}
