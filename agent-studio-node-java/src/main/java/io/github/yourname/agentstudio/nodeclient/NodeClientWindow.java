package io.github.yourname.agentstudio.nodeclient;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yourname.agentstudio.nodeclient.config.NodeConfigStore;
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
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/** Native one-button launcher for the packaged Windows node. */
final class NodeClientWindow {

    private static final String EMBEDDED_SERVER_URL = "http://127.0.0.1:8080";
    private static final String EMBEDDED_NODE_NAME = "Agent Studio Windows Node";

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
    private final JFrame frame = new JFrame("Agent Studio Node");
    private final JLabel statusDot = new JLabel("●");
    private final JLabel statusText = new JLabel("等待启动");
    private final JLabel statusDetail = new JLabel("节点尚未连接");
    private final JLabel serverValue = valueLabel(EMBEDDED_SERVER_URL);
    private final JLabel deviceValue = valueLabel(EMBEDDED_NODE_NAME);
    private final JButton actionButton = new JButton("启动");
    private volatile NodeWebSocketClient activeClient;
    private volatile Thread worker;
    private volatile boolean stopping;
    private boolean registered;

    private NodeClientWindow(Map<String, String> options, ObjectMapper objectMapper, HttpClient httpClient) {
        this.baseOptions = Map.copyOf(options);
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.configStore = new NodeConfigStore(objectMapper, AgentStudioNodeApplication.localConfigPath(options));
        loadRegistration();
        buildWindow();
    }

    static void show(Map<String, String> options, ObjectMapper objectMapper, HttpClient httpClient) {
        SwingUtilities.invokeLater(() -> {
            installSystemLookAndFeel();
            new NodeClientWindow(options, objectMapper, httpClient).frame.setVisible(true);
        });
    }

    private void loadRegistration() {
        if (!Files.exists(configStore.path())) {
            return;
        }
        try {
            NodeConfig config = configStore.load();
            registered = true;
            serverValue.setText(config.serverUrl());
            deviceValue.setText(config.name());
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
        JLabel title = new JLabel("Agent Studio Node");
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
        addDetail(details, 2, "访问范围", valueLabel("当前用户目录"));
        card.add(details, BorderLayout.CENTER);
        return card;
    }

    private JPanel actionPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        panel.setOpaque(false);
        actionButton.setPreferredSize(new Dimension(142, 42));
        actionButton.setFont(actionButton.getFont().deriveFont(Font.BOLD, 14f));
        actionButton.setForeground(Color.BLACK);
        actionButton.setBackground(BLUE);
        actionButton.setFocusPainted(false);
        actionButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        actionButton.addActionListener(event -> toggleNode());
        panel.add(actionButton);
        return panel;
    }

    private static void addDetail(JPanel panel, int row, String name, JLabel value) {
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
        actionButton.setText("停止");
        actionButton.setBackground(STOP_BACKGROUND);
        showStatus(registered ? "正在连接" : "正在注册", "请稍候...", BLUE);
        worker = Thread.ofVirtual().name("agent-studio-node-gui").start(() -> {
            try {
                if (!Files.exists(configStore.path())) {
                    AgentStudioNodeApplication.provisionLocal(options, configStore, objectMapper, httpClient);
                    registered = true;
                    SwingUtilities.invokeLater(() -> showStatus("注册成功", "正在连接服务端...", GREEN));
                }
                AgentStudioNodeApplication.start(
                        options, configStore, objectMapper, httpClient, client -> {
                            activeClient = client;
                            SwingUtilities.invokeLater(() ->
                                    showStatus("运行中", "节点正在连接并等待服务端任务", GREEN));
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
                        showStatus("启动失败", conciseMessage(ex), RED);
                        resetButton();
                    }
                });
            } finally {
                activeClient = null;
                worker = null;
            }
        });
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
        options.put("server", EMBEDDED_SERVER_URL);
        options.put("name", EMBEDDED_NODE_NAME);
        options.put("workspace", System.getProperty("user.home"));
        options.put("access", "workspace");
        return options;
    }

    private void showStopped() {
        showStatus("已停止", registered ? "节点已断开，可重新启动" : "节点尚未注册", MUTED);
        resetButton();
    }

    private void resetButton() {
        actionButton.setText("启动");
        actionButton.setBackground(BLUE);
        actionButton.setEnabled(true);
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
