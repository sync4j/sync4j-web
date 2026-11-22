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
    void testAnApplicationCanAddALanguageWithItsResources() {
        // The test resources have a sync4j-web/i18n/messages_eo.properties file, as an application can have
        assertEquals("eo", NO_SOURCE.language(null, "eo,en;q=0.5"));
        final Map<String, String> messages = NO_SOURCE.messages("eo");
        assertEquals("Elsaluti", messages.get("logout"));
        // What it does not translate is in English
        assertEquals("Login", messages.get("login.title"));
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
        // The English translations of the server
        assertEquals("The library can't be checked: {0}", i18n.messages("en").get("check.failed"));
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
    void testTextOfAMessage() {
        final Map<String, String> english = Map.of("left", "{0} tasks left", "library", "Library {0}: {1}");
        assertEquals("7 tasks left", Message.of("left", 7).format(english));
        // The arguments can be messages
        assertEquals("Library photos: 7 tasks left", Message.of("library", "photos", Message.of("left", 7)).format(english));
        // An argument can be null (the message of an exception, for instance)
        assertEquals("null tasks left", Message.of("left", (Object) null).format(english));
        // A key without translation is displayed as is
        assertEquals("unknown", Message.of("unknown", 1).format(english));
    }

    @Test
    void testEnglishCompletesTheOtherLanguages() {
        final Function<String, Map<String, String>> plugin = language -> switch (language) {
            case "en" -> Map.of("a", "A", "b", "B");
            case "fr" -> Map.of("a", "A en français");
            default -> Map.of();
        };
        final Map<String, String> french = new I18n(() -> List.of(plugin)).messages("fr");
        assertEquals("A en français", french.get("a"));
        assertEquals("B", french.get("b"));
        assertEquals("Impossible de vérifier la bibliothèque : {0}", french.get("check.failed"));
    }

    @Test
    void testEveryKeyOfTheServerHasAnEnglishText() {
        final Map<String, String> english = Translations.load(getClass().getClassLoader(), I18n.CORE_BASE, "en");
        assertTrue(english.keySet().containsAll(List.of("app.title", "check.failed", "plugin.interrupted")), english.toString());
    }
}
