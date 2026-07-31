package com.gmscpt.gui;

import com.gmscpt.agent.AiAgent;
import com.gmscpt.config.AppConfig;
import com.gmscpt.config.Constants;
import com.gmscpt.config.I18n;
import com.gmscpt.core.*;
import com.gmscpt.input.GlobalInputListener;
import com.gmscpt.input.NativeMouseInput;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * Main application window. Assembles all panels:
 * - Left top: Script editor
 * - Left bottom: Console
 * - Right: AI chat
 * - Bottom toolbar: Run, Stop, Knowledge, Settings, Status
 */
public class MainFrame extends JFrame {

    private final ScriptEditorPanel editorPanel;
    private final ConsolePanel consolePanel;
    private final ChatPanel chatPanel;
    private final JLabel statusLabel;
    private final JButton runButton;
    private final JButton stopButton;

    private final AiAgent aiAgent;
    private ScriptEngine currentEngine;
    private Thread scriptThread;

    public MainFrame() {
        super(Constants.APP_NAME + " v" + Constants.APP_VERSION);
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(1200, 750);
        setMinimumSize(new Dimension(900, 600));
        setLocationRelativeTo(null);

        // Initialize AI agent
        aiAgent = new AiAgent();

        // Create panels
        editorPanel = new ScriptEditorPanel();
        consolePanel = new ConsolePanel();
        chatPanel = new ChatPanel(aiAgent, editorPanel, consolePanel);

        // Left split: editor (top) + console (bottom)
        JSplitPane leftSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, editorPanel, consolePanel);
        leftSplit.setResizeWeight(0.65);
        leftSplit.setDividerSize(4);

        // Main split: left (editor+console) + right (chat)
        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftSplit, chatPanel);
        mainSplit.setResizeWeight(0.65);
        mainSplit.setDividerSize(4);

        add(mainSplit, BorderLayout.CENTER);

        // Bottom toolbar
        JPanel toolbar = createToolbar();
        add(toolbar, BorderLayout.SOUTH);

        // Status label
        statusLabel = new JLabel(I18n.get("status.ready"));
        statusLabel.setFont(statusLabel.getFont().deriveFont(12f));

        // Run/Stop buttons reference
        runButton = (JButton) ((JPanel) toolbar).getComponent(0);
        stopButton = (JButton) ((JPanel) toolbar).getComponent(1);
        stopButton.setEnabled(false);

        // Add status to toolbar right side
        toolbar.add(Box.createHorizontalGlue());
        toolbar.add(new JLabel(I18n.get("status.label") + ": "));
        toolbar.add(statusLabel);

        // Window close handler
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                onExit();
            }
        });

        // Setup emergency stop
        EventDispatcher.getInstance().setOnEmergencyStopCallback(this::stopScript);

        // Load last script
        String lastScript = AppConfig.getInstance().getLastScript();
        if (lastScript != null && !lastScript.isBlank()) {
            editorPanel.setScript(lastScript);
        }

        consolePanel.appendLog(I18n.get("log.welcome"));
    }

    private JPanel createToolbar() {
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));

        JButton run = new JButton(I18n.get("button.run"));
        run.setToolTipText(I18n.get("button.run.tooltip"));
        run.addActionListener(e -> runScript());

        JButton stop = new JButton(I18n.get("button.stop"));
        stop.setToolTipText(I18n.get("button.stop.tooltip"));
        stop.addActionListener(e -> stopScript());

        JButton knowledge = new JButton(I18n.get("button.knowledge"));
        knowledge.addActionListener(e -> openKnowledgeViewer());

        JButton settings = new JButton(I18n.get("button.settings"));
        settings.addActionListener(e -> openSettings());

        toolbar.add(run);
        toolbar.add(stop);
        toolbar.add(Box.createHorizontalStrut(20));
        toolbar.add(knowledge);
        toolbar.add(settings);

        return toolbar;
    }

    private void runScript() {
        String script = editorPanel.getScript();
        if (script.isBlank()) {
            setStatus(I18n.get("status.error"), true);
            consolePanel.appendLog("[ERROR] " + I18n.get("log.empty_script"));
            return;
        }

        // Auto-save script on run
        AppConfig.getInstance().setLastScript(script);
        AppConfig.getInstance().save();

        // Create context and engine
        ScriptContext context = new ScriptContext(msg -> consolePanel.appendLog(msg));
        currentEngine = new ScriptEngine(context);

        // Load script
        String error = currentEngine.load(script);
        if (error != null) {
            consolePanel.appendLog("[ERROR] " + I18n.get("log.load_failed") + ": " + error);
            setStatus(I18n.get("status.error"), true);
            return;
        }

        // Start running
        context.setRunning(true);
        EventDispatcher.getInstance().registerEngine(currentEngine);

        // Start global input listener if not already (only add listener once)
        GlobalInputListener listener = GlobalInputListener.getInstance();
        listener.start();
        if (!listener.hasListener(EventDispatcher.getInstance())) {
            listener.addListener(EventDispatcher.getInstance());
        }

        scriptThread = new Thread(() -> {
            context.setScriptThread(Thread.currentThread());
            consolePanel.appendLog(I18n.get("log.script_started"));

            // Set mouse speed to 1:1 for precise SendInput movement (restored on stop)
            NativeMouseInput.beginPreciseMode();

            // Execute script top-level code on this thread (may contain blocking loops)
            String runError = currentEngine.runMain();
            if (runError != null) {
                SwingUtilities.invokeLater(() ->
                    consolePanel.appendLog("[ERROR] " + runError));
            }

            // If script finished its top-level code (event-driven script without main loop),
            // keep thread alive so events can still be dispatched to OnEvent
            while (context.isRunning() && !context.isStopRequested()) {
                // Process queued events on script thread (LuaJ is NOT thread-safe)
                context.processPendingEvents();
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    break;
                }
            }

            // Cleanup
            NativeMouseInput.endPreciseMode();
            context.setRunning(false);
            EventDispatcher.getInstance().unregisterEngine(currentEngine);
            SwingUtilities.invokeLater(() -> {
                runButton.setEnabled(true);
                stopButton.setEnabled(false);
                if (context.isStopRequested()) {
                    setStatus(I18n.get("status.stopped"), false);
                    consolePanel.appendLog(I18n.get("log.script_stopped"));
                }
            });
        }, "ScriptThread");
        scriptThread.setDaemon(true);
        scriptThread.start();

        runButton.setEnabled(false);
        stopButton.setEnabled(true);
        setStatus(I18n.get("status.running"), false);
    }

    private void stopScript() {
        if (currentEngine != null && currentEngine.getContext().isRunning()) {
            currentEngine.getContext().requestStop();
            consolePanel.appendLog(I18n.get("log.stopping"));
        }
    }

    private void openKnowledgeViewer() {
        KnowledgeViewerDialog dialog = new KnowledgeViewerDialog(this, editorPanel);
        dialog.setVisible(true);
    }

    private void openSettings() {
        SettingsDialog dialog = new SettingsDialog(this, aiAgent, consolePanel);
        dialog.setVisible(true);
    }

    private void setStatus(String status, boolean isError) {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText(status);
            statusLabel.setForeground(isError ? Color.RED : new Color(50, 150, 50));
        });
    }

    private void onExit() {
        // Save current script
        AppConfig.getInstance().setLastScript(editorPanel.getScript());
        AppConfig.getInstance().save();

        // Stop script if running
        if (currentEngine != null && currentEngine.getContext().isRunning()) {
            currentEngine.getContext().requestStop();
        }

        // Restore mouse speed in case script was running
        NativeMouseInput.endPreciseMode();

        // Stop global listener
        GlobalInputListener.getInstance().stop();

        dispose();
        System.exit(0);
    }

    public ConsolePanel getConsolePanel() {
        return consolePanel;
    }

    public ScriptEditorPanel getEditorPanel() {
        return editorPanel;
    }
}
