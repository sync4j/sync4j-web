package com.fathzer.sync4j.web.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/** Checks that the translation files of the page are consistent, so that a translator can start from the English one. */
class TranslationFilesTest {
    private static final Path WWW = Path.of("src/main/resources/sync4j-web/www");
    private static final Pattern SCRIPT_TEXT = Pattern.compile("i18n\\.t\\('([^']+)',\\s*'((?:[^'\\\\]|\\\\.)*)'");
    private static final Pattern HTML_TEXT = Pattern.compile("data-i18n=\"([^\"]+)\"[^>]*>([^<]*)<");
    private static final Pattern HTML_ATTRIBUTE = Pattern.compile("(placeholder|title)=\"([^\"]+)\" data-i18n-(?:placeholder|title)=\"([^\"]+)\"");

    private final ClassLoader loader = getClass().getClassLoader();
    private final Map<String, String> english = Translations.load(loader, I18n.CORE_BASE, "en");

    @Test
    void testEveryTranslationHasTheKeysOfTheEnglishFile() {
        assertEquals(english.keySet(), Translations.load(loader, I18n.CORE_BASE, "fr").keySet());
    }

    @Test
    void testTheEnglishFileHasTheTextsOfThePage() throws IOException {
        // The texts of the page and of the scripts are used if the translations can't be loaded: they should be the ones of the English file
        final Map<String, String> used = new HashMap<>();
        try (Stream<Path> files = Files.walk(WWW)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".js")).toList()) {
                final Matcher matcher = SCRIPT_TEXT.matcher(Files.readString(file));
                while (matcher.find()) {
                    used.put(matcher.group(1), matcher.group(2).replace("\\'", "'"));
                }
            }
        }
        final String html = Files.readString(WWW.resolve("index.html"));
        final Matcher text = HTML_TEXT.matcher(html);
        while (text.find()) {
            used.put(text.group(1), text.group(2).strip());
        }
        final Matcher attribute = HTML_ATTRIBUTE.matcher(html);
        while (attribute.find()) {
            used.put(attribute.group(3), attribute.group(2));
        }
        assertFalse(used.isEmpty());
        used.forEach((key, value) -> assertEquals(value, english.get(key), "Text of " + key + " in the English file"));
        // The badges of the states are built from their keys (state.RUNNING, ...)
        assertTrue(english.keySet().containsAll(java.util.List.of("state.RUNNING", "state.DONE", "state.FAILED", "state.UNKNOWN", "state.CANCELLED")));
    }
}
