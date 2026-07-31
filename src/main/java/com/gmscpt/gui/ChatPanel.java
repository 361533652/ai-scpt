package com.gmscpt.gui;

import com.gmscpt.agent.AiAgent;
import com.gmscpt.agent.LlmClient;
import com.gmscpt.config.I18n;
import com.gmscpt.knowledge.GameData;
import com.gmscpt.knowledge.KnowledgeBase;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI chat panel with message cards, collapsible thinking, and dark theme.
 */
public class ChatPanel extends JPanel {

    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile("```(?:lua)?\\s*\\n?(.*?)```", Pattern.DOTALL);

    // Dark theme colors - harmonized with editor(40,44,52) and console(30,30,30)
    private static final Color BG_MAIN = new Color(38, 40, 44);
    private static final Color BG_USER_CARD = new Color(45, 58, 78);
    private static final Color BG_AI_CARD = new Color(48, 51, 56);
    private static final Color BG_THINKING = new Color(34, 36, 40);
    private static final Color BORDER_USER = new Color(70, 100, 150);
    private static final Color BORDER_AI = new Color(70, 75, 82);
    private static final Color TEXT_PRIMARY = new Color(220, 222, 225);
    private static final Color TEXT_SECONDARY = new Color(150, 155, 162);
    private static final Color TEXT_THINKING = new Color(130, 135, 142);
    private static final Color ACCENT = new Color(90, 140, 210);

    private final JPanel messagesContainer;
    private final JScrollPane scrollPane;
    private final JTextField inputField;
    private final JButton sendButton;
    private final JButton attachButton;
    private final JLabel attachIndicator;
    private final AiAgent aiAgent;
    private final ScriptEditorPanel editorPanel;
    private final ConsolePanel consolePanel;
    private final Set<GameData> selectedKnowledge = new HashSet<>();

    public ChatPanel(AiAgent aiAgent, ScriptEditorPanel editorPanel, ConsolePanel consolePanel) {
        this.aiAgent = aiAgent;
        this.editorPanel = editorPanel;
        this.consolePanel = consolePanel;
        setLayout(new BorderLayout(0, 0));
        setBackground(BG_MAIN);

        // Title bar
        JPanel titleBar = new JPanel(new BorderLayout());
        titleBar.setBackground(BG_MAIN);
        titleBar.setBorder(new EmptyBorder(8, 12, 6, 12));
        JLabel title = new JLabel(I18n.get("chat.title"));
        title.setFont(title.getFont().deriveFont(Font.BOLD, 13f));
        title.setForeground(TEXT_PRIMARY);
        titleBar.add(title, BorderLayout.WEST);
        add(titleBar, BorderLayout.NORTH);

        // Messages area
        messagesContainer = new JPanel();
        messagesContainer.setLayout(new BoxLayout(messagesContainer, BoxLayout.Y_AXIS));
        messagesContainer.setBackground(BG_MAIN);
        messagesContainer.setBorder(new EmptyBorder(8, 10, 8, 10));

        scrollPane = new JScrollPane(messagesContainer);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.getViewport().setBackground(BG_MAIN);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        add(scrollPane, BorderLayout.CENTER);

        // Input area
        JPanel inputPanel = new JPanel(new BorderLayout(6, 4));
        inputPanel.setBackground(BG_MAIN);
        inputPanel.setBorder(new EmptyBorder(6, 10, 10, 10));

        // Top row: attach button + indicator
        JPanel attachRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        attachRow.setBackground(BG_MAIN);
        attachButton = new JButton("+");
        attachButton.setFont(new Font("Microsoft YaHei", Font.BOLD, 14));
        attachButton.setToolTipText(I18n.get("chat.attach_knowledge"));
        attachButton.setMargin(new Insets(1, 6, 1, 6));
        attachButton.addActionListener(e -> showKnowledgePopup());
        attachIndicator = new JLabel();
        attachIndicator.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        attachIndicator.setForeground(TEXT_SECONDARY);
        attachRow.add(attachButton);
        attachRow.add(attachIndicator);
        inputPanel.add(attachRow, BorderLayout.NORTH);

        // Bottom row: input + send
        JPanel inputRow = new JPanel(new BorderLayout(6, 0));
        inputRow.setBackground(BG_MAIN);
        inputField = new JTextField();
        inputField.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        inputField.setToolTipText(I18n.get("chat.input_hint"));
        inputField.setBackground(new Color(52, 55, 60));
        inputField.setForeground(TEXT_PRIMARY);
        inputField.setCaretColor(TEXT_PRIMARY);
        inputField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(65, 68, 74), 1, true),
                new EmptyBorder(6, 10, 6, 10)
        ));

        sendButton = new JButton(I18n.get("chat.send"));
        sendButton.addActionListener(e -> sendMessage());

        inputRow.add(inputField, BorderLayout.CENTER);
        inputRow.add(sendButton, BorderLayout.EAST);
        inputPanel.add(inputRow, BorderLayout.CENTER);
        add(inputPanel, BorderLayout.SOUTH);

        // Enter to send
        inputField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    sendMessage();
                }
            }
        });

        // Welcome message as AI card
        addAiMessageCard(I18n.get("chat.welcome"), null);
    }

    // ==================== Message Card Builders ====================

    private JPanel createUserCard(String message) {
        JPanel card = new JPanel(new BorderLayout());
        card.setBackground(BG_USER_CARD);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_USER, 1, true),
                new EmptyBorder(10, 12, 10, 12)
        ));
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        JLabel label = new JLabel(I18n.get("chat.you"));
        label.setFont(label.getFont().deriveFont(Font.BOLD, 11f));
        label.setForeground(ACCENT);
        card.add(label, BorderLayout.NORTH);

        JTextArea content = createContentArea(message);
        card.add(content, BorderLayout.CENTER);
        return card;
    }

    private JPanel createAiCard(String content, String thinking) {
        JPanel card = new JPanel(new BorderLayout(0, 6));
        card.setBackground(BG_AI_CARD);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_AI, 1, true),
                new EmptyBorder(10, 12, 10, 12)
        ));
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        JLabel label = new JLabel("AI");
        label.setFont(label.getFont().deriveFont(Font.BOLD, 11f));
        label.setForeground(new Color(100, 200, 130));
        card.add(label, BorderLayout.NORTH);

        JPanel bodyPanel = new JPanel();
        bodyPanel.setLayout(new BoxLayout(bodyPanel, BoxLayout.Y_AXIS));
        bodyPanel.setBackground(BG_AI_CARD);

        // Collapsible thinking section
        if (thinking != null && !thinking.isBlank()) {
            bodyPanel.add(createThinkingSection(thinking));
            bodyPanel.add(Box.createVerticalStrut(6));
        }

        JTextArea contentArea = createContentArea(content);
        bodyPanel.add(contentArea);
        card.add(bodyPanel, BorderLayout.CENTER);
        return card;
    }

    private JPanel createThinkingSection(String thinkingText) {
        JPanel section = new JPanel(new BorderLayout());
        section.setBackground(BG_AI_CARD);
        section.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        // Toggle header
        JLabel toggle = new JLabel("▶ " + I18n.get("chat.thinking"));
        toggle.setFont(toggle.getFont().deriveFont(Font.PLAIN, 11f));
        toggle.setForeground(TEXT_THINKING);
        toggle.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        // Thinking content (hidden by default)
        JTextArea thinkArea = new JTextArea(thinkingText);
        thinkArea.setEditable(false);
        thinkArea.setLineWrap(true);
        thinkArea.setWrapStyleWord(true);
        thinkArea.setFont(new Font("Microsoft YaHei", Font.ITALIC, 12));
        thinkArea.setBackground(BG_THINKING);
        thinkArea.setForeground(TEXT_THINKING);
        thinkArea.setBorder(new EmptyBorder(8, 10, 8, 10));
        thinkArea.setCaretPosition(0);

        JScrollPane thinkScroll = new JScrollPane(thinkArea);
        thinkScroll.setBorder(BorderFactory.createLineBorder(new Color(55, 58, 63), 1, true));
        thinkScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 150));
        thinkScroll.setPreferredSize(new Dimension(0, 150));
        thinkScroll.setVisible(false);

        toggle.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                boolean visible = !thinkScroll.isVisible();
                thinkScroll.setVisible(visible);
                toggle.setText((visible ? "▼ " : "▶ ") + I18n.get("chat.thinking"));
                section.revalidate();
                section.repaint();
            }
        });

        section.add(toggle, BorderLayout.NORTH);
        section.add(thinkScroll, BorderLayout.CENTER);
        return section;
    }

    private JTextArea createContentArea(String text) {
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        area.setForeground(TEXT_PRIMARY);
        area.setOpaque(false);
        area.setBorder(null);
        return area;
    }

    private void addMessageCard(JPanel card) {
        messagesContainer.add(card);
        messagesContainer.add(Box.createVerticalStrut(10));
        messagesContainer.revalidate();
        scrollToBottom();
    }

    private void addAiMessageCard(String content, String thinking) {
        addMessageCard(createAiCard(content, thinking));
    }

    private void scrollToBottom() {
        SwingUtilities.invokeLater(() -> {
            JScrollBar bar = scrollPane.getVerticalScrollBar();
            bar.setValue(bar.getMaximum());
        });
    }

    // ==================== Streaming Message Card ====================

    /**
     * A live-updating AI message card for streaming display.
     */
    private static class StreamCard {
        final JPanel card;
        final JPanel bodyPanel;
        final JTextArea contentArea;
        final JLabel thinkingToggle;
        final JTextArea thinkingArea;
        final JScrollPane thinkingScroll;
        final JPanel thinkingSection;
        boolean thinkingVisible = false;
        boolean hasThinking = false;

        StreamCard(String thinkingLabel) {
            card = new JPanel(new BorderLayout(0, 6));
            card.setBackground(BG_AI_CARD);
            card.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(BORDER_AI, 1, true),
                    new EmptyBorder(10, 12, 10, 12)
            ));
            card.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

            JLabel label = new JLabel("AI");
            label.setFont(label.getFont().deriveFont(Font.BOLD, 11f));
            label.setForeground(new Color(100, 200, 130));
            card.add(label, BorderLayout.NORTH);

            bodyPanel = new JPanel();
            bodyPanel.setLayout(new BoxLayout(bodyPanel, BoxLayout.Y_AXIS));
            bodyPanel.setBackground(BG_AI_CARD);

            // Thinking section (initially hidden)
            thinkingSection = new JPanel(new BorderLayout());
            thinkingSection.setBackground(BG_AI_CARD);
            thinkingSection.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

            thinkingToggle = new JLabel("▶ " + thinkingLabel);
            thinkingToggle.setFont(thinkingToggle.getFont().deriveFont(Font.PLAIN, 11f));
            thinkingToggle.setForeground(TEXT_THINKING);
            thinkingToggle.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            thinkingArea = new JTextArea();
            thinkingArea.setEditable(false);
            thinkingArea.setLineWrap(true);
            thinkingArea.setWrapStyleWord(true);
            thinkingArea.setFont(new Font("Microsoft YaHei", Font.ITALIC, 12));
            thinkingArea.setBackground(BG_THINKING);
            thinkingArea.setForeground(TEXT_THINKING);
            thinkingArea.setBorder(new EmptyBorder(8, 10, 8, 10));

            thinkingScroll = new JScrollPane(thinkingArea);
            thinkingScroll.setBorder(BorderFactory.createLineBorder(new Color(55, 58, 63), 1, true));
            thinkingScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 150));
            thinkingScroll.setPreferredSize(new Dimension(0, 150));
            thinkingScroll.setVisible(false);

            thinkingToggle.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseClicked(java.awt.event.MouseEvent e) {
                    thinkingVisible = !thinkingVisible;
                    thinkingScroll.setVisible(thinkingVisible);
                    thinkingToggle.setText((thinkingVisible ? "▼ " : "▶ ") + thinkingLabel);
                    thinkingSection.revalidate();
                    thinkingSection.repaint();
                }
            });

            thinkingSection.add(thinkingToggle, BorderLayout.NORTH);
            thinkingSection.add(thinkingScroll, BorderLayout.CENTER);
            thinkingSection.setVisible(false);

            bodyPanel.add(thinkingSection);

            // Content area
            contentArea = new JTextArea();
            contentArea.setEditable(false);
            contentArea.setLineWrap(true);
            contentArea.setWrapStyleWord(true);
            contentArea.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
            contentArea.setForeground(TEXT_PRIMARY);
            contentArea.setOpaque(false);
            contentArea.setBorder(null);
            bodyPanel.add(contentArea);

            card.add(bodyPanel, BorderLayout.CENTER);
        }

        void appendThinking(String chunk) {
            if (!hasThinking) {
                hasThinking = true;
                thinkingSection.setVisible(true);
            }
            thinkingArea.append(chunk);
        }

        void appendContent(String chunk) {
            contentArea.append(chunk);
            contentArea.setCaretPosition(contentArea.getDocument().getLength());
        }
    }

    // ==================== Core Logic ====================

    private void showKnowledgePopup() {
        List<GameData> allEntries = KnowledgeBase.getInstance().getAllEntries();
        if (allEntries.isEmpty()) return;

        JPopupMenu popup = new JPopupMenu();
        for (GameData entry : allEntries) {
            JCheckBoxMenuItem item = new JCheckBoxMenuItem(entry.toString(), selectedKnowledge.contains(entry));
            item.addActionListener(e -> {
                if (item.isSelected()) {
                    selectedKnowledge.add(entry);
                } else {
                    selectedKnowledge.remove(entry);
                }
                updateAttachIndicator();
            });
            popup.add(item);
        }
        popup.show(attachButton, 0, -popup.getPreferredSize().height);
    }

    private void updateAttachIndicator() {
        if (selectedKnowledge.isEmpty()) {
            attachIndicator.setText("");
        } else {
            attachIndicator.setText(I18n.get("chat.attached_count", String.valueOf(selectedKnowledge.size())));
        }
    }

    private void sendMessage() {
        String message = inputField.getText().trim();
        if (message.isEmpty()) return;

        inputField.setText("");
        sendButton.setEnabled(false);
        inputField.setEnabled(false);

        // Add user message card
        addMessageCard(createUserCard(message));

        // Build knowledge context
        String knowledgeContext = null;
        if (!selectedKnowledge.isEmpty()) {
            knowledgeContext = KnowledgeBase.getInstance()
                    .getFormattedContent(new ArrayList<>(selectedKnowledge));
        }

        // Create streaming AI card
        StreamCard streamCard = new StreamCard(I18n.get("chat.thinking"));
        addMessageCard(streamCard.card);

        // Call AI in background thread
        final String finalKnowledge = knowledgeContext;
        new Thread(() -> {
            try {
                consolePanel.appendLog(I18n.get("log.ai_request", truncate(message, 50)));

                StringBuilder thinkingBuilder = new StringBuilder();
                String response = aiAgent.sendMessageStream(message, finalKnowledge, new LlmClient.StreamCallback() {
                    @Override
                    public void onThinking(String chunk) {
                        thinkingBuilder.append(chunk);
                        SwingUtilities.invokeLater(() -> {
                            streamCard.appendThinking(chunk);
                            scrollToBottom();
                        });
                    }

                    @Override
                    public void onContent(String chunk) {
                        SwingUtilities.invokeLater(() -> {
                            streamCard.appendContent(chunk);
                            scrollToBottom();
                        });
                    }
                });

                consolePanel.appendLog(I18n.get("log.ai_response", truncate(response, 80)));

                // After streaming done, check for code blocks
                SwingUtilities.invokeLater(() -> {
                    messagesContainer.revalidate();
                    scrollToBottom();
                    Matcher matcher = CODE_BLOCK_PATTERN.matcher(response);
                    StringBuilder allCode = new StringBuilder();
                    while (matcher.find()) {
                        if (allCode.length() > 0) allCode.append("\n\n");
                        allCode.append(matcher.group(1).trim());
                    }
                    if (allCode.length() > 0) {
                        showInsertCodeOption(allCode.toString());
                    }
                });
            } catch (Exception ex) {
                consolePanel.appendLog("[ERROR] " + I18n.get("log.ai_error", ex.getMessage()));
                SwingUtilities.invokeLater(() -> {
                    streamCard.appendContent("\n[ERROR] " + ex.getMessage());
                    messagesContainer.revalidate();
                    scrollToBottom();
                });
            } finally {
                SwingUtilities.invokeLater(() -> {
                    sendButton.setEnabled(true);
                    inputField.setEnabled(true);
                    inputField.requestFocus();
                });
            }
        }, "AiChatThread").start();
    }

    private void showInsertCodeOption(String code) {
        int choice = JOptionPane.showConfirmDialog(
                this,
                I18n.get("chat.insert_code_confirm"),
                I18n.get("chat.insert_code_title"),
                JOptionPane.YES_NO_OPTION
        );
        if (choice == JOptionPane.YES_OPTION) {
            editorPanel.setScript(code);
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        s = s.replace("\n", " ");
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
