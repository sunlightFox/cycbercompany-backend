package io.github.yourname.agentstudio.nodeclient;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yourname.agentstudio.nodeclient.config.NodeConfigStore;
import io.github.yourname.agentstudio.nodeclient.config.NodeProcessLock;
import io.github.yourname.agentstudio.nodeclient.transport.NodeWebSocketClient;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/** Native one-button launcher for the packaged Windows node. */
final class NodeClientWindow {

    private static final String EMBEDDED_SERVER_URL = "http://127.0.0.1:8080";
    private static final String EMBEDDED_NODE_NAME = "CycberCompany Windows Node";

    private static final Color PAGE = new Color(243, 246, 249);
    private static final Color SURFACE = Color.WHITE;
    private static final Color TEXT = new Color(27, 35, 46);
    private static final Color MUTED = new Color(102, 112, 126);
    private static final Color BORDER = new Color(220, 225, 231);
    private static final Color BLUE = new Color(218, 233, 255);
    private static final Color STOP_BACKGROUND = new Color(255, 226, 226);
    private static final Color GREEN = new Color(28, 145, 86);
    private static final Color RED = new Color(200, 55, 55);

    private final Map<String, String> baseOptions;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final NodeConfigStore configStore;
    private final WindowsLoginStartup windowsLoginStartup;
    private final JFrame frame = new JFrame("CycberCompany Node");
    private final JLabel statusDot = new JLabel("●");
    private final JLabel statusText = new JLabel("等待启动");
    private final JLabel statusDetail = new JLabel("节点尚未连接");
    private final JLabel serverValue;
    private final JLabel deviceValue;
    private final JLabel workspaceValue;
    private final JButton workspaceButton = new JButton("选择...");
    private final JButton actionButton = new JButton("启动");
    private JCheckBox loginStartupButton;
    private volatile NodeWebSocketClient activeClient;
    private volatile Thread worker;
    private volatile boolean stopping;
    private boolean registered;
    private String selectedWorkspace;

    private NodeClientWindow(Map<String, String> options, ObjectMapper objectMapper, HttpClient httpClient) {
        this.baseOptions = Map.copyOf(options);
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.configStore = new NodeConfigStore(objectMapper, AgentStudioNodeApplication.localConfigPath(options));
        this.windowsLoginStartup = WindowsLoginStartup.forPackagedApp().orElse(null);
        Map<String, String> resolved = resolvedOptions(options);
        this.serverValue = valueLabel(resolved.get("server"));
        this.deviceValue = valueLabel(resolved.get("name"));
        this.selectedWorkspace = resolved.get("workspace");
        this.workspaceValue = valueLabel(selectedWorkspace);
        loadRegistration();
        buildWindow();
    }

    static void show(Map<String, String> options, ObjectMapper objectMapper, HttpClient httpClient) {
        SwingUtilities.invokeLater(() -> {
            installSystemLookAndFeel();
            NodeClientWindow window = new NodeClientWindow(options, objectMapper, httpClient);
            // Login startup stays unobtrusive once the user has already confirmed a workspace.
            // Surface the window again if configuration was lost so recovery is still possible.
            if (!runsInBackground(options) || !window.registered) {
                window.frame.setVisible(true);
            }
            if (window.shouldAutoStart()) {
                window.startNode();
            }
        });
    }

    private void loadRegistration() {
        if (!Files.exists(configStore.path())) {
            statusText.setText("准备启动");
            statusDetail.setText("选择项目目录后点击启动，首次连接会自动完成配置。");
            return;
        }
        try {
            NodeConfig config = configStore.load();
            registered = true;
            serverValue.setText(config.serverUrl());
            deviceValue.setText(config.name());
            selectedWorkspace = config.workspaceRoot();
            workspaceValue.setText(selectedWorkspace);
            statusText.setText("已注册");
            statusDetail.setText("点击启动连接服务端");
        } catch (Exception ex) {
            statusText.setText("配置不可用");
            statusDetail.setText(conciseMessage(ex));
            statusDot.setForeground(RED);
        }
    }

    private void buildWindow() {
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setResizable(false);
        frame.setSize(540, 430);
        frame.setLocationRelativeTo(null);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                stopNode(false);
            }
        });

        JPanel root = new JPanel(new BorderLayout(0, 22));
        root.setBackground(PAGE);
        root.setBorder(BorderFactory.createEmptyBorder(28, 32, 28, 32));
        root.add(header(), BorderLayout.NORTH);
        root.add(statusPanel(), BorderLayout.CENTER);
        root.add(actionPanel(), BorderLayout.SOUTH);
        frame.setContentPane(root);
    }

    private JPanel header() {
        JPanel panel = new JPanel(new BorderLayout(0, 5));
        panel.setOpaque(false);
        JLabel title = new JLabel("CycberCompany Node");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 24f));
        title.setForeground(TEXT);
        JLabel subtitle = new JLabel("Windows 节点客户端");
        subtitle.setFont(subtitle.getFont().deriveFont(13f));
        subtitle.setForeground(MUTED);
        panel.add(title, BorderLayout.NORTH);
        panel.add(subtitle, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel statusPanel() {
        JPanel card = new JPanel(new BorderLayout(0, 22));
        card.setBackground(SURFACE);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                BorderFactory.createEmptyBorder(24, 24, 22, 24)));

        JPanel status = new JPanel(new BorderLayout(14, 0));
        status.setOpaque(false);
        statusDot.setForeground(registered ? GREEN : MUTED);
        statusDot.setFont(statusDot.getFont().deriveFont(18f));
        status.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER));

        JPanel labels = new JPanel(new BorderLayout(0, 4));
        labels.setOpaque(false);
        statusText.setFont(statusText.getFont().deriveFont(Font.BOLD, 17f));
        statusText.setForeground(TEXT);
        statusDetail.setFont(statusDetail.getFont().deriveFont(12f));
        statusDetail.setForeground(MUTED);
        labels.add(statusText, BorderLayout.NORTH);
        labels.add(statusDetail, BorderLayout.SOUTH);
        labels.setBorder(BorderFactory.createEmptyBorder(0, 0, 18, 0));
        status.add(statusDot, BorderLayout.WEST);
        status.add(labels, BorderLayout.CENTER);
        card.add(status, BorderLayout.NORTH);

        JPanel details = new JPanel(new GridBagLayout());
        details.setOpaque(false);
        addDetail(details, 0, "服务地址", serverValue);
        addDetail(details, 1, "设备名称", deviceValue);
        addDetail(details, 2, "项目目录", workspaceSelector());
        card.add(details, BorderLayout.CENTER);
        return card;
    }

    private JComponent workspaceSelector() {
        JPanel panel = new JPanel(new BorderLayout(8, 0));
        panel.setOpaque(false);
        workspaceValue.setToolTipText(selectedWorkspace);
        panel.add(workspaceValue, BorderLayout.CENTER);
        workspaceButton.setFocusPainted(false);
        workspaceButton.addActionListener(event -> chooseWorkspace());
        panel.add(workspaceButton, BorderLayout.EAST);
        return panel;
    }

    private void chooseWorkspace() {
        JFileChooser chooser = new JFileChooser(selectedWorkspace);
        chooser.setDialogTitle("选择项目工作目录");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        selectedWorkspace = chooser.getSelectedFile().toPath().toAbsolutePath().normalize().toString();
        workspaceValue.setText(selectedWorkspace);
        workspaceValue.setToolTipText(selectedWorkspace);
    }

    private JPanel actionPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);
        if (windowsLoginStartup != null) {
            JCheckBox startWithWindows = new JCheckBox("\u767b\u5f55 Windows \u540e\u81ea\u52a8\u8fde\u63a5");
            loginStartupButton = startWithWindows;
            startWithWindows.setOpaque(false);
            startWithWindows.setForeground(MUTED);
            startWithWindows.setEnabled(canConfigureLoginStartup(registered));
            startWithWindows.setToolTipText("\u53ef\u5728 Windows \u7684 Startup \u6587\u4ef6\u5939\u4e2d\u968f\u65f6\u5220\u9664");
            try {
                startWithWindows.setSelected(windowsLoginStartup.isEnabled());
            } catch (IOException ex) {
                startWithWindows.setEnabled(false);
                startWithWindows.setToolTipText(conciseMessage(ex));
            }
            startWithWindows.addActionListener(event -> updateLoginStartup(startWithWindows));
            panel.add(startWithWindows, BorderLayout.WEST);
        }
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        actions.setOpaque(false);
        actionButton.setPreferredSize(new Dimension(142, 42));
        actionButton.setFont(actionButton.getFont().deriveFont(Font.BOLD, 14f));
        actionButton.setForeground(Color.BLACK);
        actionButton.setBackground(BLUE);
        actionButton.setFocusPainted(false);
        actionButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        actionButton.addActionListener(event -> toggleNode());
        actions.add(actionButton);
        panel.add(actions, BorderLayout.EAST);
        return panel;
    }

    private void updateLoginStartup(JCheckBox startWithWindows) {
        try {
            windowsLoginStartup.setEnabled(startWithWindows.isSelected());
        } catch (IOException ex) {
            startWithWindows.setSelected(!startWithWindows.isSelected());
            showStatus("\u65e0\u6cd5\u4fdd\u5b58\u542f\u52a8\u9009\u9879", conciseMessage(ex), RED);
        }
    }

    private static void addDetail(JPanel panel, int row, String name, JComponent value) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridy = row;
        constraints.insets = new Insets(5, 0, 5, 12);
        constraints.anchor = GridBagConstraints.WEST;
        JLabel label = new JLabel(name);
        label.setForeground(MUTED);
        panel.add(label, constraints);

        constraints.gridx = 1;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.insets = new Insets(5, 12, 5, 0);
        panel.add(value, constraints);
    }

    private void toggleNode() {
        if (worker != null && worker.isAlive()) {
            stopNode(true);
        } else {
            startNode();
        }
    }

    private void startNode() {
        Map<String, String> options;
        try {
            options = fixedOptions();
        } catch (IllegalArgumentException ex) {
            showStatus("无法启动", ex.getMessage(), RED);
            return;
        }

        stopping = false;
        workspaceButton.setEnabled(false);
        actionButton.setText("停止");
        actionButton.setBackground(STOP_BACKGROUND);
        showStatus(registered ? "正在连接" : "正在注册", "请稍候...", BLUE);
        worker = Thread.ofVirtual().name("agent-studio-node-gui").start(() -> {
            try (NodeProcessLock ignored = NodeProcessLock.acquire(configStore.path())) {
                if (shouldReprovisionOnStart(baseOptions, registered)) {
                    // A damaged or user-bound protected config must not prevent an explicit
                    // Start action from rebuilding this managed-local companion. Only a config
                    // that was successfully loaded is safe to synchronize before replacement.
                    if (registered) {
                        synchronizeSelectedWorkspace(options);
                    }
                    // A visible Start repairs a control-plane reset or credential rotation.
                    provisionLocalWithRetry(options);
                    registered = true;
                }
                SwingUtilities.invokeLater(() -> showStatus("正在连接", "正在验证本机执行器...", BLUE));
                AgentStudioNodeApplication.startWithAcquiredProcessLock(
                        options, configStore, objectMapper, httpClient, client -> {
                            activeClient = client;
                            client.setConnectionObserver(connected -> SwingUtilities.invokeLater(() -> {
                                if (connected) {
                                    enableLoginStartupOption();
                                    showStatus("运行中", "本机执行器已连接，正在等待任务", GREEN);
                                } else if (!stopping) {
                                    showStatus("正在重连", "与服务端的连接暂时中断，正在恢复...", BLUE);
                                }
                            }));
                            SwingUtilities.invokeLater(() ->
                                    showStatus("正在连接", "正在验证本机执行器...", BLUE));
                        });
                SwingUtilities.invokeLater(this::showStopped);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                SwingUtilities.invokeLater(this::showStopped);
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    if (stopping) {
                        showStopped();
                    } else {
                        showStatus("启动失败", startupFailureDetail(ex), RED);
                        resetButton();
                    }
                });
            } finally {
                activeClient = null;
                worker = null;
            }
        });
    }

    private void provisionLocalWithRetry(Map<String, String> options) throws Exception {
        BootstrapRetryPolicy.execute(
                () -> {
                AgentStudioNodeApplication.provisionLocal(options, configStore, objectMapper, httpClient);
                },
                () -> stopping,
                nextAttempt -> {
                SwingUtilities.invokeLater(() -> showStatus(
                        "等待服务端",
                        "控制面尚未就绪，正在重试 " + nextAttempt + "/" + BootstrapRetryPolicy.MAX_ATTEMPTS + "...",
                        BLUE));
                },
                Thread::sleep);
    }

    static int retryDelayMillis(int attempt) {
        return BootstrapRetryPolicy.delayMillis(attempt);
    }

    static boolean isTransientStartupFailure(Throwable error) {
        return BootstrapRetryPolicy.isTransientFailure(error);
    }

    static String startupFailureDetail(Throwable error) {
        String message = conciseMessage(error);
        String normalized = message.toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("already running for this config")) {
            return "\u672c\u673a\u6267\u884c\u5668\u5df2\u5728\u8fd0\u884c\uff0c\u65e0\u9700\u91cd\u590d\u542f\u52a8\u3002";
        }
        if (normalized.contains("http 401") || normalized.contains("http 403")) {
            return "服务端需要身份验证。请为当前 Windows 用户配置 AGENT_STUDIO_API_TOKEN 后重试。";
        }
        if (normalized.contains("registered nodes only")) {
            return "当前服务端要求使用受管节点。请联系管理员完成节点注册。";
        }
        if (normalized.contains("workspace must be an existing directory")) {
            return "工作目录不可用。请重新选择一个仍存在的项目目录。";
        }
        if (normalized.contains("access is denied") || normalized.contains("permission denied")) {
            return "本地配置目录不可写。请检查当前 Windows 用户对配置目录的权限后重试。";
        }
        if (normalized.matches(".*http [5][0-9][0-9].*")) {
            return "服务端暂时不可用，请稍后重试。";
        }
        return message;
    }

    private void stopNode(boolean updateUi) {
        stopping = true;
        if (updateUi) {
            actionButton.setEnabled(false);
            showStatus("正在停止", "正在关闭节点连接...", MUTED);
        }
        NodeWebSocketClient client = activeClient;
        if (client != null) {
            client.stop();
        }
        Thread currentWorker = worker;
        if (currentWorker != null) {
            currentWorker.interrupt();
        }
        if (updateUi && currentWorker == null) {
            showStopped();
        }
    }

    private Map<String, String> fixedOptions() {
        Map<String, String> options = new LinkedHashMap<>(baseOptions);
        options.put("workspace", selectedWorkspace);
        return resolvedOptions(options);
    }

    /** Keeps a user-selected project directory authoritative across future launches. */
    private void synchronizeSelectedWorkspace(Map<String, String> options) throws Exception {
        NodeConfig existing = configStore.load();
        Path workspace = AgentStudioNodeApplication.workspacePath(options);
        if (workspace.toString().equals(existing.workspaceRoot())) {
            return;
        }
        configStore.save(new NodeConfig(
                existing.serverUrl(),
                existing.nodeId(),
                existing.nodeSecret(),
                existing.websocketUrl(),
                existing.name(),
                workspace.toString(),
                existing.accessMode()));
    }

    private boolean shouldAutoStart() {
        return shouldAutoStart(baseOptions, registered);
    }

    static boolean shouldAutoStart(Map<String, String> options, boolean registered) {
        // A generic installer cannot know the user's project. First launch must let the user
        // confirm the workspace; later launches reconnect without another prompt. Deployment
        // shortcuts can still deliberately suppress this with --no-auto-start.
        return registered && !booleanOption(options, "no-auto-start");
    }

    static boolean runsInBackground(Map<String, String> options) {
        return booleanOption(options, "background");
    }

    static boolean shouldReprovisionOnStart(Map<String, String> options, boolean registered) {
        return !registered || !runsInBackground(options);
    }

    static boolean canConfigureLoginStartup(boolean registered) {
        return registered;
    }

    private void enableLoginStartupOption() {
        if (loginStartupButton != null) {
            loginStartupButton.setEnabled(true);
        }
    }

    static Map<String, String> resolvedOptions(Map<String, String> configured) {
        Map<String, String> options = new LinkedHashMap<>(configured);
        options.computeIfAbsent("server", ignored -> EMBEDDED_SERVER_URL);
        options.computeIfAbsent("name", ignored -> EMBEDDED_NODE_NAME);
        options.computeIfAbsent("workspace", ignored -> System.getProperty("user.home"));
        options.put("access", "workspace");
        return options;
    }

    private static boolean booleanOption(Map<String, String> options, String name) {
        String value = options.get(name);
        return value != null && (value.isBlank() || "true".equalsIgnoreCase(value) || "1".equals(value));
    }

    private void showStopped() {
        showStatus("已停止", registered ? "节点已断开，可重新启动" : "节点尚未注册", MUTED);
        resetButton();
    }

    private void resetButton() {
        actionButton.setText("启动");
        actionButton.setBackground(BLUE);
        actionButton.setEnabled(true);
        workspaceButton.setEnabled(true);
    }

    private void showStatus(String title, String detail, Color color) {
        statusText.setText(title);
        statusDetail.setText(detail);
        statusDot.setForeground(color);
    }

    private static JLabel valueLabel(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(TEXT);
        label.setHorizontalAlignment(SwingConstants.LEFT);
        return label;
    }

    private static String conciseMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && (current.getMessage() == null || current.getMessage().isBlank())) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private static void installSystemLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }
    }
}
