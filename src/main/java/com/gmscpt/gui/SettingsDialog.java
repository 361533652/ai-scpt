package com.gmscpt.gui;

import com.gmscpt.agent.AiAgent;
import com.gmscpt.config.AppConfig;
import com.gmscpt.config.I18n;

import javax.swing.*;
import java.awt.*;

/**
 * Settings dialog for configuring API key, base URL, model.
 */
public class SettingsDialog extends JDialog {

    private final JTextField apiKeyField;
    private final JTextField baseUrlField;
    private final JTextField modelNameField;
    private final AiAgent aiAgent;
    private final ConsolePanel consolePanel;
    private final JButton testBtn;

    public SettingsDialog(Frame owner, AiAgent aiAgent, ConsolePanel consolePanel) {
        super(owner, I18n.get("settings.title"), true);
        this.aiAgent = aiAgent;
        this.consolePanel = consolePanel;
        setSize(450, 260);
        setLocationRelativeTo(owner);
        setResizable(false);

        AppConfig config = AppConfig.getInstance();

        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;

        // API Key
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
        panel.add(new JLabel(I18n.get("settings.apikey")), gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        apiKeyField = new JPasswordField(20);
        apiKeyField.setText(config.getApiKey());
        panel.add(apiKeyField, gbc);

        // Base URL
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0;
        panel.add(new JLabel(I18n.get("settings.baseurl")), gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        baseUrlField = new JTextField(20);
        baseUrlField.setText(config.getApiBaseUrl());
        baseUrlField.setToolTipText(I18n.get("settings.baseurl_hint"));
        panel.add(baseUrlField, gbc);

        // Model Name
        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0;
        panel.add(new JLabel(I18n.get("settings.model")), gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        modelNameField = new JTextField(20);
        modelNameField.setText(config.getModelName());
        panel.add(modelNameField, gbc);

        add(panel, BorderLayout.CENTER);

        // Buttons
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        testBtn = new JButton(I18n.get("settings.test"));
        testBtn.addActionListener(e -> testConnection());
        JButton saveBtn = new JButton(I18n.get("settings.save"));
        saveBtn.addActionListener(e -> save());
        JButton cancelBtn = new JButton(I18n.get("settings.cancel"));
        cancelBtn.addActionListener(e -> dispose());
        buttonPanel.add(testBtn);
        buttonPanel.add(saveBtn);
        buttonPanel.add(cancelBtn);
        add(buttonPanel, BorderLayout.SOUTH);
    }

    private void save() {
        AppConfig config = AppConfig.getInstance();
        config.setApiKey(apiKeyField.getText().trim());
        config.setApiBaseUrl(baseUrlField.getText().trim());
        config.setModelName(modelNameField.getText().trim());
        config.save();

        // Rebuild AI client with new settings
        aiAgent.rebuildClient();

        JOptionPane.showMessageDialog(this,
                I18n.get("settings.saved"),
                I18n.get("settings.title"),
                JOptionPane.INFORMATION_MESSAGE);
        dispose();
    }

    private void testConnection() {
        // Immediate UI feedback
        testBtn.setEnabled(false);
        testBtn.setText(I18n.get("settings.testing"));
        consolePanel.appendLog(I18n.get("log.test_start"));

        // Temporarily apply fields to config for testing
        AppConfig config = AppConfig.getInstance();
        String oldKey = config.getApiKey();
        String oldUrl = config.getApiBaseUrl();
        String oldModel = config.getModelName();

        config.setApiKey(apiKeyField.getText().trim());
        config.setApiBaseUrl(baseUrlField.getText().trim());
        config.setModelName(modelNameField.getText().trim());
        aiAgent.rebuildClient();

        new Thread(() -> {
            String result;
            boolean success;
            try {
                String reply = aiAgent.ping();
                result = I18n.get("settings.test_success") + "\n\n" + I18n.get("settings.test_reply") + ": " + truncate(reply, 80);
                success = true;
                consolePanel.appendLog(I18n.get("log.test_success") + " - " + truncate(reply, 50));
            } catch (Exception ex) {
                result = I18n.get("settings.test_failed") + "\n\n" + ex.getMessage();
                success = false;
                consolePanel.appendLog("[ERROR] " + I18n.get("log.test_failed") + " - " + ex.getMessage());
            }

            // Restore if failed
            boolean finalSuccess = success;
            String finalResult = result;
            SwingUtilities.invokeLater(() -> {
                // Restore button state
                testBtn.setEnabled(true);
                testBtn.setText(I18n.get("settings.test"));

                if (!finalSuccess) {
                    // Restore old config
                    config.setApiKey(oldKey);
                    config.setApiBaseUrl(oldUrl);
                    config.setModelName(oldModel);
                    aiAgent.rebuildClient();
                }
                JOptionPane.showMessageDialog(this, finalResult,
                        I18n.get("settings.test"),
                        finalSuccess ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.ERROR_MESSAGE);
            });
        }, "TestConnectionThread").start();
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
