package com.gmscpt.gui;

import com.gmscpt.config.Constants;
import com.gmscpt.config.I18n;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Console/log panel with dark terminal-style background.
 * Shows timestamped log messages, auto-scrolls, and limits line count.
 */
public class ConsolePanel extends JPanel {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int MAX_LINES = Constants.MAX_CONSOLE_LINES;

    private final JTextPane textPane;
    private final StyledDocument doc;
    private final Style defaultStyle;
    private final Style errorStyle;
    private int lineCount = 0;

    public ConsolePanel() {
        setLayout(new BorderLayout());

        // Header with clear button
        JPanel header = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 2));
        header.setBackground(new Color(30, 30, 30));
        JButton clearBtn = new JButton(I18n.get("console.clear"));
        clearBtn.setFont(clearBtn.getFont().deriveFont(11f));
        clearBtn.setMargin(new Insets(1, 6, 1, 6));
        clearBtn.addActionListener(e -> clear());
        header.add(clearBtn);
        add(header, BorderLayout.NORTH);

        // Text pane
        textPane = new JTextPane();
        textPane.setEditable(false);
        textPane.setBackground(new Color(25, 25, 25));
        textPane.setForeground(new Color(200, 200, 200));
        textPane.setFont(new Font("Consolas", Font.PLAIN, 13));
        textPane.setCaretColor(Color.WHITE);

        doc = textPane.getStyledDocument();

        // Styles
        defaultStyle = textPane.addStyle("default", null);
        StyleConstants.setForeground(defaultStyle, new Color(200, 200, 200));

        errorStyle = textPane.addStyle("error", null);
        StyleConstants.setForeground(errorStyle, new Color(255, 100, 100));

        JScrollPane scrollPane = new JScrollPane(textPane);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        add(scrollPane, BorderLayout.CENTER);
    }

    /**
     * Append a log message with timestamp.
     */
    public void appendLog(String message) {
        SwingUtilities.invokeLater(() -> {
            try {
                String timestamp = "[" + LocalTime.now().format(TIME_FMT) + "] ";
                Style style = message.contains("[ERROR]") ? errorStyle : defaultStyle;

                doc.insertString(doc.getLength(), timestamp + message + "\n", style);
                lineCount++;

                // Trim if too many lines
                if (lineCount > MAX_LINES) {
                    trimLines(lineCount - MAX_LINES);
                    lineCount = MAX_LINES;
                }

                // Auto-scroll to bottom
                textPane.setCaretPosition(doc.getLength());
            } catch (BadLocationException e) {
                // Ignore
            }
        });
    }

    private void trimLines(int linesToRemove) {
        try {
            Element root = doc.getDefaultRootElement();
            int endOffset = root.getElement(linesToRemove - 1).getEndOffset();
            doc.remove(0, endOffset);
        } catch (BadLocationException e) {
            // Ignore
        }
    }

    public void clear() {
        SwingUtilities.invokeLater(() -> {
            try {
                doc.remove(0, doc.getLength());
                lineCount = 0;
            } catch (BadLocationException e) {
                // Ignore
            }
        });
    }
}
