package com.fathzer.sync4j.web.i18n;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The translations of the web page: the ones of the page itself, then the ones of the plugins and of the application (that can replace the previous ones).
 * <br>The language is the first language of the user (the <code>Accept-Language</code> header sent by the browser, that is the one of the system)
 * that has translations, English otherwise.
 */
public final class I18n {
    private static final Logger log = LoggerFactory.getLogger(I18n.class);
    /** The base name of the resources with the translations of the page. */
    public static final String CORE_BASE = "sync4j-web/i18n/messages";
    /** The language of the texts that are in the code. */
    public static final String DEFAULT_LANGUAGE = "en";

    private final Supplier<List<Function<String, Map<String, String>>>> sources;

    /**
     * Constructor.
     * @param sources provides the sources of translations that complete the ones of the page, in order (a source can replace the previous ones). A source gives the translations of a language.
     */
    public I18n(Supplier<List<Function<String, Map<String, String>>>> sources) {
        this.sources = sources;
    }

    /**
     * Chooses the language.
     * @param requested the language explicitly requested (null if none)
     * @param acceptLanguage the value of the <code>Accept-Language</code> header (null if none)
     * @return the language
     */
    public String language(String requested, String acceptLanguage) {
        // English is always available (it completes the other languages)
        if (isSupported(requested)) {
            return requested;
        }
        if (acceptLanguage != null) {
            try {
                for (Locale.LanguageRange range : Locale.LanguageRange.parse(acceptLanguage)) {
                    final String language = range.getRange().toLowerCase(Locale.ROOT).split("-")[0];
                    if (isSupported(language)) {
                        return language;
                    }
                }
            } catch (IllegalArgumentException e) {
                log.debug("Ignored invalid Accept-Language: {}", acceptLanguage);
            }
        }
        return DEFAULT_LANGUAGE;
    }

    private static boolean isSupported(String language) {
        return DEFAULT_LANGUAGE.equals(language) || Translations.exists(I18n.class.getClassLoader(), CORE_BASE, language);
    }

    /**
     * Gets the translations of a language.
     * @param language the language (see {@link #language(String, String)})
     * @return the translations of the page, completed by the ones of the plugins and the application
     */
    public Map<String, String> messages(String language) {
        // English completes the other languages: a key that is not translated is displayed in English
        final List<String> languages = DEFAULT_LANGUAGE.equals(language) ? List.of(DEFAULT_LANGUAGE) : List.of(DEFAULT_LANGUAGE, language);
        final List<Function<String, Map<String, String>>> all = new ArrayList<>();
        all.add(lang -> Translations.load(I18n.class.getClassLoader(), CORE_BASE, lang));
        all.addAll(sources.get());
        final Map<String, String> result = new HashMap<>();
        for (String lang : languages) {
            for (Function<String, Map<String, String>> source : all) {
                try {
                    result.putAll(source.apply(lang));
                } catch (RuntimeException e) {
                    log.warn("Can't load translations ({})", lang, e);
                }
            }
        }
        return result;
    }
}
