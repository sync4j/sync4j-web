package com.fathzer.sync4j.web.plugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A text that is displayed to the user in his language.
 * <br>The text is the translation of the key (see {@link SyncPlugin#translations(String)}): a pattern whose <code>{0}</code>, <code>{1}</code>, ... are replaced
 * by the arguments (that can be messages too). There are no other special characters in these patterns (an apostrophe is an apostrophe).
 * <br>The translations of a language are completed by the English ones: a key needs at least an English translation. If it has none, the key itself is displayed.
 * <br>A message is sent to the page as <code>{"key":"...","args":[...]}</code>, and the page translates it. A text that does not need to be translated is a simple string.
 * @param key the key of the translation
 * @param args the arguments of the pattern (strings, numbers or messages, null is displayed as <code>null</code>)
 */
public record Message(String key, List<Object> args) {
    /** Constructor. */
    public Message {
        Objects.requireNonNull(key);
        // An argument can be null (for instance, the message of an exception)
        args = args == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(args));
    }

    /**
     * Creates a message.
     * @param key the key of the translation
     * @param args the arguments of the pattern
     * @return a new message
     */
    public static Message of(String key, Object... args) {
        return new Message(key, Arrays.asList(args));
    }

    /**
     * Gets the text of this message in a language (for instance, the English one for a log).
     * @param translations the translations of the language (see {@link SyncPlugin#translations(String)})
     * @return the text. The key is used as pattern if it has no translation.
     */
    public String format(Map<String, String> translations) {
        return format(translations.getOrDefault(key, key), args.stream().map(arg -> arg instanceof Message message ? message.format(translations) : arg).toList());
    }

    /**
     * Replaces the placeholders of a pattern by arguments.
     * @param pattern the pattern, with <code>{0}</code>, <code>{1}</code>, ...
     * @param args the arguments
     * @return the text. A placeholder that has no argument is left as is.
     */
    public static String format(String pattern, List<?> args) {
        final StringBuilder result = new StringBuilder();
        int i = 0;
        while (i < pattern.length()) {
            final char c = pattern.charAt(i);
            final int end = c == '{' ? pattern.indexOf('}', i) : -1;
            if (end > i + 1 && pattern.substring(i + 1, end).chars().allMatch(Character::isDigit)) {
                final int index = Integer.parseInt(pattern.substring(i + 1, end));
                result.append(index < args.size() ? String.valueOf(args.get(index)) : pattern.substring(i, end + 1));
                i = end + 1;
            } else {
                result.append(c);
                i++;
            }
        }
        return result.toString();
    }
}
