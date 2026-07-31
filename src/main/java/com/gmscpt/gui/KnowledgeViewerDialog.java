package com.gmscpt.gui;

import com.gmscpt.config.I18n;
import com.gmscpt.knowledge.GameData;
import com.gmscpt.knowledge.KnowledgeBase;

import javax.swing.*;
import javax.swing.event.ListSelectionEvent;
import java.awt.*;
import java.util.List;

/**
 * Dialog for viewing the built-in knowledge base (script examples/templates).
 */
public class KnowledgeViewerDialog extends JDialog {

    private final JList<GameData> list;
    private final DefaultListModel<GameData> listModel;
    private final JTextArea detailArea;
    private final JTextField searchField;
    private final ScriptEditorPanel editorPanel;

    public KnowledgeViewerDialog(Frame owner, ScriptEditorPanel editorPanel) {
        super(owner, I18n.get("knowledge.title"), true);
        this.editorPanel = editorPanel;
        setSize(700, 500);
        setLocationRelativeTo(owner);
        setLayout(new BorderLayout(8, 8));

        // Search panel
        JPanel searchPanel = new JPanel(new BorderLayout(4, 0));
        searchPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        searchField = new JTextField();
        searchField.setToolTipText(I18n.get("knowledge.search_hint"));
        JButton searchBtn = new JButton(I18n.get("knowledge.search"));
        searchBtn.addActionListener(e -> doSearch());
        searchField.addActionListener(e -> doSearch());
        searchPanel.add(searchField, BorderLayout.CENTER);
        searchPanel.add(searchBtn, BorderLayout.EAST);
        add(searchPanel, BorderLayout.NORTH);

        // List
        listModel = new DefaultListModel<>();
        list = new JList<>(listModel);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean sel, boolean focus) {
                super.getListCellRendererComponent(l, value, index, sel, focus);
                if (value instanceof GameData gd) {
                    setText("[" + gd.getGame() + "] " + gd.getTitle());
                }
                return this;
            }
        });
        list.addListSelectionListener(this::onSelectionChanged);

        JScrollPane listScroll = new JScrollPane(list);
        listScroll.setPreferredSize(new Dimension(250, 0));

        // Detail area
        detailArea = new JTextArea();
        detailArea.setEditable(false);
        detailArea.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        detailArea.setLineWrap(true);
        detailArea.setWrapStyleWord(true);
        detailArea.setMargin(new Insets(8, 8, 8, 8));
        JScrollPane detailScroll = new JScrollPane(detailArea);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listScroll, detailScroll);
        split.setDividerLocation(250);
        add(split, BorderLayout.CENTER);

        // Bottom buttons
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        JButton insertBtn = new JButton(I18n.get("knowledge.insert"));
        insertBtn.addActionListener(e -> insertToEditor());
        JButton closeBtn = new JButton(I18n.get("knowledge.close"));
        closeBtn.addActionListener(e -> dispose());
        buttonPanel.add(insertBtn);
        buttonPanel.add(closeBtn);
        add(buttonPanel, BorderLayout.SOUTH);

        // Load all entries
        loadEntries(KnowledgeBase.getInstance().getAllEntries());
    }

    private void doSearch() {
        String keyword = searchField.getText().trim();
        List<GameData> results = KnowledgeBase.getInstance().search(keyword);
        loadEntries(results);
    }

    private void loadEntries(List<GameData> entries) {
        listModel.clear();
        for (GameData entry : entries) {
            listModel.addElement(entry);
        }
    }

    private void onSelectionChanged(ListSelectionEvent e) {
        if (e.getValueIsAdjusting()) return;
        GameData selected = list.getSelectedValue();
        if (selected != null) {
            StringBuilder sb = new StringBuilder();
            sb.append("Game: ").append(selected.getGame()).append("\n");
            sb.append("Title: ").append(selected.getTitle()).append("\n");
            sb.append("Description: ").append(selected.getDescription()).append("\n");
            if (selected.getTags() != null) {
                sb.append("Tags: ").append(String.join(", ", selected.getTags())).append("\n");
            }
            sb.append("\n--- Script ---\n\n");
            sb.append(selected.getScript());
            detailArea.setText(sb.toString());
            detailArea.setCaretPosition(0);
        }
    }

    private void insertToEditor() {
        GameData selected = list.getSelectedValue();
        if (selected != null && selected.getScript() != null) {
            editorPanel.setScript(selected.getScript());
            dispose();
        }
    }
}
