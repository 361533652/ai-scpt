package com.gmscpt.config;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * Internationalization utility based on ResourceBundle.
 */
public final class I18n {

    private static ResourceBundle bundle;

    private I18n() {}

    public static void init(Locale locale) {
        bundle = ResourceBundle.getBundle("i18n.messages", locale);
    }

    public static void init() {
        init(Locale.getDefault());
    }

    public static String get(String key) {
        try {
            return bundle.getString(key);
        } catch (MissingResourceException e) {
            return "!" + key + "!";
        }
    }

    public static String get(String key, Object... args) {
        String pattern = get(key);
        return MessageFormat.format(pattern, args);
    }

    public static Locale getCurrentLocale() {
        return bundle.getLocale();
    }
}
