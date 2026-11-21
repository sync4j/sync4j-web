package com.fathzer.sync4j.web.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.fathzer.sync4j.web.plugin.Message;

class I18nTest {
    private static final I18n NO_SOURCE = new I18n(List::of);

    @Test
    void testLanguageIsTheFirstOneOfTheUserThatHasTranslations() {
        assertEquals("fr", NO_SOURCE.language(null, "fr-FR,fr;q=0.9,en;q=0.8"));
        // The weights decide: English comes first, so the user has the texts that are in the code
        assertEquals("en", NO_SOURCE.language(null, "en-US,fr;q=0.5"));
        // A language that has no translations is skipped
        assertEquals("fr", NO_SOURCE.language(null, "de-DE,fr;q=0.5"));
        assertEquals("en", NO_SOURCE.language(null, "de-DE,es;q=0.5"));
    }

    @Test
    void testEnglishWhenTheLanguageIsNotKnown() {
        assertEquals("en", NO_SOURCE.language(null, null));
        assertEquals("en", NO_SOURCE.language(null, ""));
        assertEquals("en", NO_SOURCE.language(null, "*"));
        assertEquals("en", NO_SOURCE.language(null, "this is not a language;;;"));
    }

    @Test
    void testRequestedLanguage() {
        assertEquals("fr", NO_SOURCE.language("fr", "en"));
        assertEquals("en", NO_SOURCE.language("en", "fr"));
        // An unknown or invalid language is ignored (it is used in the name of a resource)
        assertEquals("fr", NO_SOURCE.language("de", "fr"));
        assertEquals("en", NO_SOURCE.language("../../secret", null));
        assertEquals("en", NO_SOURCE.language("FR", null));
    }

    @Test
    void testTheTranslationsOfThePageAreCompletedByTheOnesOfTheSources() {
        final Function<String, Map<String, String>> plugin = language -> "fr".equals(language) ? Map.of("plugin.key", "Du plugin", "logout", "Quitter") : Map.of();
        final I18n i18n = new I18n(() -> List.of(plugin));

        final Map<String, String> messages = i18n.messages("fr");
        assertEquals("Du plugin", messages.get("plugin.key"));
        // A source replaces what is before it
        assertEquals("Quitter", messages.get("logout"));
        assertEquals("Connexion", messages.get("login.title"));
        // English has no translation: the texts of the code are used
        assertTrue(i18n.messages("en").isEmpty());
    }

    @Test
    void testASourceThatFailsDoesNotPreventTheOthers() {
        final Function<String, Map<String, String>> broken = language -> {
            throw new IllegalStateException("broken");
        };
        final I18n i18n = new I18n(() -> List.of(broken, language -> Map.of("other", "Autre")));

        assertEquals("Autre", i18n.messages("fr").get("other"));
        assertEquals("Connexion", i18n.messages("fr").get("login.title"));
    }

    @Test
    void testLoadTranslations() {
        final ClassLoader loader = getClass().getClassLoader();
        final Map<String, String> messages = Translations.load(loader, "test-i18n/messages", "fr");
        // The file is UTF-8, an apostrophe is an apostrophe
        assertEquals("Bonjour {0}, l'état est « {1} »", messages.get("greeting"));
        assertEquals(2, messages.size());
        assertTrue(Translations.load(loader, "test-i18n/messages", "de").isEmpty());
        assertTrue(Translations.load(loader, "test-i18n/messages", "../x").isEmpty());
        assertFalse(Translations.exists(loader, "test-i18n/messages", "de"));
    }

    @Test
    void testEveryKeyOfTheFrenchTranslationHasAText() {
        Translations.load(getClass().getClassLoader(), I18n.CORE_BASE, "fr").forEach((key, text) -> assertFalse(text.isBlank(), key));
    }

    @Test
    void testMessageFormat() {
        assertEquals("Bonjour Alice, 3 !", Message.format("Bonjour {0}, {1} !", List.of("Alice", 3)));
        // A placeholder without argument, and what is not a placeholder, are left as they are
        assertEquals("{1} {x} {} { 0}", Message.format("{1} {x} {} { 0}", List.of("a")));
        assertEquals("a a", Message.format("{0} {0}", List.of("a")));
        // The arguments are not interpreted
        assertEquals("{1} b", Message.format("{0} {1}", List.of("{1}", "b")));
        assertEquals("It's a", Message.format("It's {0}", List.of("a")));
    }

    @Test
    void testMessageToStringIsTheEnglishText() {
        assertEquals("7 tasks left", Message.of("k", "{0} tasks left", 7).toString());
        assertEquals("Library photos: 7 tasks left", Message.of("k", "Library {0}: {1}", "photos", Message.of("k2", "{0} tasks left", 7)).toString());
        assertEquals("plain", Message.text("plain").toString());
    }
}
