package com.gmscpt;

import com.formdev.flatlaf.FlatDarkLaf;
import com.gmscpt.config.AppConfig;
import com.gmscpt.config.I18n;
import com.gmscpt.gui.MainFrame;

import javax.swing.*;
import java.util.Locale;

/**
 * Application entry point.
 * Initializes config, i18n, look-and-feel, and launches the main window.
 */
public class App {

    public static void main(String[] args) {
        // Load config
        AppConfig config = AppConfig.getInstance();

        // Initialize i18n
        String lang = config.getLanguage();
        Locale locale = parseLocale(lang);
        I18n.init(locale);

        // Set look and feel
        try {
            UIManager.setLookAndFeel(new FlatDarkLaf());
        } catch (Exception e) {
            System.err.println("Failed to set FlatLaf: " + e.getMessage());
            // Fallback to system L&F
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ex) {
                // Use default
            }
        }

        // Launch GUI on EDT
        SwingUtilities.invokeLater(() -> {
            MainFrame frame = new MainFrame();
            frame.setVisible(true);
        });
    }

    private static Locale parseLocale(String lang) {
        if (lang == null || lang.isBlank()) return Locale.getDefault();
        String[] parts = lang.split("_");
        if (parts.length == 2) {
            return new Locale(parts[0], parts[1]);
        }
        return new Locale(parts[0]);
    }
}
