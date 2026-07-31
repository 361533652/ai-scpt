package com.gmscpt.gui;

import com.gmscpt.config.I18n;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.UndoableEditEvent;
import javax.swing.text.*;
import javax.swing.undo.UndoManager;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Script editor panel with Lua syntax highlighting, undo/redo,
 * auto-indent, formatting, save/load, and right-click context menu.
 */
public class ScriptEditorPanel extends JPanel {

    private final JTextPane editor;
    private final StyledDocument doc;
    private final UndoManager undoManager;
    private Consumer<String> onContentChanged;
    private boolean highlighting = false;
    private final Timer highlightTimer;
    private Path currentFile;

    // Syntax colors (dark theme)
    private static final Color COLOR_BG = new Color(40, 44, 52);
    private static final Color COLOR_DEFAULT = new Color(220, 220, 220);
    private static final Color COLOR_KEYWORD = new Color(198, 120, 221);
    private static final Color COLOR_COMMENT = new Color(106, 153, 85);
    private static final Color COLOR_STRING = new Color(206, 145, 100);
    private static final Color COLOR_NUMBER = new Color(140, 190, 240);
    private static final Color COLOR_API = new Color(222, 197, 95);

    // Regex patterns for Lua syntax
    private static final Pattern COMMENT_PATTERN = Pattern.compile("--[^\n]*");
    private static final Pattern STRING_PATTERN = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"");
    private static final Pattern KEYWORD_PATTERN = Pattern.compile(
            "\\b(function|end|if|then|else|elseif|while|do|for|in|repeat|until|return|break|local|and|or|not|true|false|nil)\\b"
    );
    private static final Pattern API_PATTERN = Pattern.compile(
            "\\b(OnEvent|PressKey|ReleaseKey|TapKey|IsKeyPressed|PressAndReleaseKey|" +
            "PressMouseButton|ReleaseMouseButton|ClickMouseButton|IsMouseButtonPressed|" +
            "MoveMouseTo|MoveMouseRelative|GetMousePosition|MouseWheelScroll|" +
            "Sleep|Log|OutputLogMessage|GetRunningTime|GetTime|IsScriptRunning|RandomInt)\\b"
    );
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\b(\\d+\\.?\\d*)\\b");

    // Lua keywords that increase indent
    private static final Pattern INDENT_INCREASE = Pattern.compile(
            "\\b(function|if|then|else|elseif|while|do|for|repeat)\\b"
    );
    private static final Pattern INDENT_DECREASE = Pattern.compile(
            "^\\s*(end|else|elseif|until)\\b"
    );

    public ScriptEditorPanel() {
        setLayout(new BorderLayout());

        // Editor label
        JLabel label = new JLabel("  " + I18n.get("editor.title"));
        label.setFont(label.getFont().deriveFont(Font.BOLD, 12f));
        add(label, BorderLayout.NORTH);

        // Text pane
        editor = new JTextPane();
        doc = editor.getStyledDocument();
        editor.setFont(new Font("Microsoft YaHei Mono", Font.PLAIN, 14));
        editor.setBackground(COLOR_BG);
        editor.setForeground(COLOR_DEFAULT);
        editor.setCaretColor(Color.WHITE);
        editor.setSelectionColor(new Color(60, 100, 180));
        editor.setSelectedTextColor(Color.WHITE);
        editor.setMargin(new Insets(8, 8, 8, 8));

        // Default character style
        Style defaultStyle = doc.addStyle("default", null);
        StyleConstants.setForeground(defaultStyle, COLOR_DEFAULT);
        StyleConstants.setFontFamily(defaultStyle, "Microsoft YaHei Mono");
        StyleConstants.setFontSize(defaultStyle, 14);

        // Tab inserts 4 spaces
        editor.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), "insert-tab");
        editor.getActionMap().put("insert-tab", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                editor.replaceSelection("    ");
            }
        });

        // Enter = auto-indent
        editor.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "auto-indent");
        editor.getActionMap().put("auto-indent", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                doAutoIndent();
            }
        });

        // Undo/Redo
        undoManager = new UndoManager();
        undoManager.setLimit(500);
        doc.addUndoableEditListener((UndoableEditEvent e) -> undoManager.addEdit(e.getEdit()));

        InputMap im = editor.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap am = editor.getActionMap();
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK), "undo");
        am.put("undo", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (undoManager.canUndo()) undoManager.undo();
            }
        });
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK), "redo");
        am.put("redo", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (undoManager.canRedo()) undoManager.redo();
            }
        });

        // Ctrl+S = save
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK), "save-file");
        am.put("save-file", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                saveFile();
            }
        });

        // Ctrl+Shift+F = format
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), "format-code");
        am.put("format-code", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                formatCode();
            }
        });

        // Right-click context menu
        editor.setComponentPopupMenu(createContextMenu());

        // Debounced highlighting
        highlightTimer = new Timer(100, e -> applyHighlighting());
        highlightTimer.setRepeats(false);

        // Document listener
        doc.addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { onChange(); }
            @Override public void removeUpdate(DocumentEvent e) { onChange(); }
            @Override public void changedUpdate(DocumentEvent e) { onChange(); }
        });

        // Default script
        editor.setText(getDefaultScript());
        undoManager.discardAllEdits();
        applyHighlighting();

        JScrollPane scrollPane = new JScrollPane(editor);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        add(scrollPane, BorderLayout.CENTER);
    }

    // ==================== Context Menu ====================

    private JPopupMenu createContextMenu() {
        JPopupMenu menu = new JPopupMenu();

        JMenuItem saveItem = new JMenuItem("保存 (Ctrl+S)");
        saveItem.addActionListener(e -> saveFile());
        menu.add(saveItem);

        JMenuItem saveAsItem = new JMenuItem("另存为...");
        saveAsItem.addActionListener(e -> saveFileAs());
        menu.add(saveAsItem);

        JMenuItem openItem = new JMenuItem("打开...");
        openItem.addActionListener(e -> openFile());
        menu.add(openItem);

        menu.addSeparator();

        JMenuItem formatItem = new JMenuItem("格式化 (Ctrl+Shift+F)");
        formatItem.addActionListener(e -> formatCode());
        menu.add(formatItem);

        menu.addSeparator();

        JMenuItem undoItem = new JMenuItem("撤销 (Ctrl+Z)");
        undoItem.addActionListener(e -> { if (undoManager.canUndo()) undoManager.undo(); });
        menu.add(undoItem);

        JMenuItem redoItem = new JMenuItem("重做 (Ctrl+Y)");
        redoItem.addActionListener(e -> { if (undoManager.canRedo()) undoManager.redo(); });
        menu.add(redoItem);

        menu.addSeparator();

        JMenuItem selectAllItem = new JMenuItem("全选 (Ctrl+A)");
        selectAllItem.addActionListener(e -> editor.selectAll());
        menu.add(selectAllItem);

        return menu;
    }

    // ==================== Auto Indent ====================

    private void doAutoIndent() {
        try {
            int caretPos = editor.getCaretPosition();
            int lineStart = Utilities.getRowStart(editor, caretPos);
            String lineText = doc.getText(lineStart, caretPos - lineStart);

            // Get current line's leading whitespace
            String indent = getLeadingWhitespace(lineText);

            // Check if current line ends with indent-increasing keyword
            String trimmed = lineText.trim();
            if (INDENT_INCREASE.matcher(trimmed).find() && !trimmed.startsWith("--")) {
                // Check if line also has "end" (e.g. one-liner function)
                if (!trimmed.matches(".*\\bend\\s*$") && !trimmed.matches(".*\\bend\\b.*")) {
                    indent += "    ";
                }
            }

            editor.replaceSelection("\n" + indent);
        } catch (BadLocationException ex) {
            editor.replaceSelection("\n");
        }
    }

    private String getLeadingWhitespace(String text) {
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (c == ' ' || c == '\t') sb.append(c);
            else break;
        }
        return sb.toString();
    }

    // ==================== Format Code ====================

    private void formatCode() {
        String text = editor.getText();
        String formatted = formatLua(text);
        int caretLine = getCaretLine();
        editor.setText(formatted);
        // Try to restore caret to same line
        try {
            int pos = getLineStartOffset(caretLine);
            editor.setCaretPosition(Math.min(pos, formatted.length()));
        } catch (Exception ignored) {
            editor.setCaretPosition(0);
        }
        undoManager.discardAllEdits();
        applyHighlighting();
    }

    private String formatLua(String code) {
        String[] lines = code.split("\n", -1);
        StringBuilder result = new StringBuilder();
        int indentLevel = 0;
        String indentUnit = "    ";

        for (String rawLine : lines) {
            String line = rawLine.trim();

            if (line.isEmpty()) {
                result.append("\n");
                continue;
            }

            // Decrease indent for lines starting with end/else/elseif/until
            if (INDENT_DECREASE.matcher(line).find()) {
                indentLevel = Math.max(0, indentLevel - 1);
            }

            result.append(indentUnit.repeat(indentLevel)).append(line).append("\n");

            // Increase indent after lines containing function/if/then/else/while/do/for/repeat
            // But not if the line also ends with "end"
            if (!line.startsWith("--") && INDENT_INCREASE.matcher(line).find()) {
                // Don't increase if line ends with "end" or is a one-liner
                if (!line.matches(".*\\bend\\s*$") && !line.matches(".*\\bend\\b\\s*[,)]?\\s*$")) {
                    // "else" and "elseif" already decreased, so net effect is same level
                    if (line.matches("^(else|elseif)\\b.*")) {
                        indentLevel++;
                    } else {
                        indentLevel++;
                    }
                }
            }
        }

        // Remove trailing newline
        if (result.length() > 0 && result.charAt(result.length() - 1) == '\n') {
            result.setLength(result.length() - 1);
        }
        return result.toString();
    }

    private int getCaretLine() {
        int pos = editor.getCaretPosition();
        return doc.getDefaultRootElement().getElementIndex(pos);
    }

    private int getLineStartOffset(int line) {
        var root = doc.getDefaultRootElement();
        if (line >= root.getElementCount()) return doc.getLength();
        return root.getElement(line).getStartOffset();
    }

    // ==================== File Save/Load ====================

    private void saveFile() {
        if (currentFile != null) {
            doSave(currentFile);
        } else {
            saveFileAs();
        }
    }

    private void saveFileAs() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("保存脚本");
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Lua脚本 (*.lua)", "lua", "txt"));
        if (currentFile != null) chooser.setSelectedFile(currentFile.toFile());
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            Path path = chooser.getSelectedFile().toPath();
            if (!path.toString().contains(".") ) {
                path = Path.of(path + ".lua");
            }
            doSave(path);
        }
    }

    private void doSave(Path path) {
        try {
            Files.writeString(path, editor.getText());
            currentFile = path;
            JOptionPane.showMessageDialog(this, "已保存: " + path.getFileName(), "保存成功", JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "保存失败: " + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void openFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("打开脚本");
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Lua脚本 (*.lua)", "lua", "txt"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                Path path = chooser.getSelectedFile().toPath();
                String content = Files.readString(path);
                setScript(content);
                currentFile = path;
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this, "打开失败: " + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    // ==================== Syntax Highlighting ====================

    private void onChange() {
        if (onContentChanged != null) {
            onContentChanged.accept(editor.getText());
        }
        highlightTimer.restart();
    }

    private void applyHighlighting() {
        if (highlighting) return;
        highlighting = true;
        try {
            String text = editor.getText();
            int len = text.length();
            if (len == 0) return;

            SimpleAttributeSet def = new SimpleAttributeSet();
            StyleConstants.setForeground(def, COLOR_DEFAULT);
            StyleConstants.setBold(def, false);
            StyleConstants.setItalic(def, false);
            doc.setCharacterAttributes(0, len, def, true);

            highlight(text, NUMBER_PATTERN, COLOR_NUMBER, false, false);
            highlight(text, API_PATTERN, COLOR_API, false, false);
            highlight(text, KEYWORD_PATTERN, COLOR_KEYWORD, true, false);
            highlight(text, STRING_PATTERN, COLOR_STRING, false, false);
            highlight(text, COMMENT_PATTERN, COLOR_COMMENT, false, true);
        } finally {
            highlighting = false;
        }
    }

    private void highlight(String text, Pattern pattern, Color color, boolean bold, boolean italic) {
        SimpleAttributeSet attrs = new SimpleAttributeSet();
        StyleConstants.setForeground(attrs, color);
        StyleConstants.setBold(attrs, bold);
        StyleConstants.setItalic(attrs, italic);

        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            doc.setCharacterAttributes(matcher.start(), matcher.end() - matcher.start(), attrs, true);
        }
    }

    // ==================== Public API ====================

    public String getScript() {
        return editor.getText();
    }

    public void setScript(String script) {
        editor.setText(script);
        editor.setCaretPosition(0);
        undoManager.discardAllEdits();
        applyHighlighting();
    }

    public void appendScript(String script) {
        int pos = editor.getCaretPosition();
        editor.select(pos, pos);
        editor.replaceSelection(script);
    }

    public void setOnContentChanged(Consumer<String> callback) {
        this.onContentChanged = callback;
    }

    public JTextPane getEditor() {
        return editor;
    }

    private String getDefaultScript() {
        return """
                -- GmScript Example
                -- OnEvent is called on mouse/keyboard events
                
                function OnEvent(event, arg)
                    -- Example: rapid fire on left mouse button hold
                    if event == "MOUSE_BUTTON_PRESSED" and arg == 1 then
                        Log("Left mouse pressed - starting rapid fire")
                        while IsMouseButtonPressed(1) and IsScriptRunning() do
                            ClickMouseButton(1)
                            Sleep(100)
                        end
                    end
                end
                """;
    }
}
