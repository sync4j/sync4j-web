package com.fathzer.sync4j.web.plugin;

import java.util.List;

/**
 * A text that is displayed to the user and that the web page can translate.
 * <br>The page looks for the translation of the key in its dictionary (see {@link SyncPlugin#translations(String)}). If it has none, it displays the fallback.
 * The fallback and the translations are patterns: <code>{0}</code>, <code>{1}</code>, ... are replaced by the arguments (that can be messages too).
 * There are no other special characters in these patterns (an apostrophe is an apostrophe).
 * <br>A message is sent to the page as <code>{"key":"...","fallback":"...","args":[...]}</code>.
 * @param key the key of the translation (null if the text can't be translated)
 * @param fallback the text (a pattern) displayed if the key has no translation, usually the English one
 * @param args the arguments of the pattern (strings, numbers or messages)
 */
public record Message(String key, String fallback, List<Object> args) {
    /** Constructor. */
    public Message {
        args = args == null ? List.of() : List.copyOf(args);
    }

    /**
     * Creates a message that can be translated.
     * @param key the key of the translation
     * @param fallback the text (a pattern) displayed if the key has no translation
     * @param args the arguments of the pattern
     * @return a new message
     */
    public static Message of(String key, String fallback, Object... args) {
        return new Message(key, fallback, List.of(args));
    }

    /**
     * Creates a message that can't be translated.
     * @param text the text
     * @return a new message
     */
    public static Message text(String text) {
        return new Message(null, text, List.of());
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

    /** The text without translation, with the arguments in place (for logs and tests). */
    @Override
    public String toString() {
        return format(fallback == null ? String.valueOf(key) : fallback, args);
    }
}
