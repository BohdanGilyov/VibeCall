
package absolutely.real;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

import javax.sound.sampled.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.*;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.prefs.Preferences;

public class Main {

    private static final String PC = "192.168.50.1";
    private static final String SNOM = "192.168.50.2";

    private static final int PHONE_PORT = 47622;
    private static final int MICROSIP_PORT = 47621;
    private static final int CHROME_PORT = 47624;
    private static final int WHISPER_PORT = 47630;
    private static final int SAMPLE_RATE = 16000;

    private static final Path HISTORY =
            Path.of("transcripts").toAbsolutePath().normalize();

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern(
                    "yyyy-MM-dd_HH-mm-ss_SSS"
            );

    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final Color BG =
            new Color(11, 18, 31);

    private static final Color CARD =
            new Color(23, 36, 55);

    private static final Color SECOND =
            new Color(38, 56, 79);

    private static final Color FG =
            new Color(243, 248, 255);

    private static final Color MUTED =
            new Color(154, 173, 195);

    private static final Color CYAN =
            new Color(81, 224, 197);

    private static final Color BLUE =
            new Color(127, 182, 255);

    private final JFrame frame =
            new JFrame("Snom AI Bridge 5.1");

    private final JLabel status =
            label("Готов · сними трубку", 18, true, FG);

    private final JLabel phoneInfo =
            label(
                    "Snom: запускаю HTTP-сервер",
                    12, false, MUTED
            );

    private final JLabel audioInfo =
            label("Vosk: загрузка", 12, false, MUTED);

    private final JLabel asrInfo =
            label("Whisper: запуск...", 12, false, MUTED);

    private final JLabel chromeInfo =
            label(
                    "Chrome: ожидаю расширение",
                    12, false, MUTED
            );

    private final JLabel preview =
            label(
                    "Слова появятся здесь",
                    13, false, BLUE
            );

    private final JLabel timer =
            label("00:00", 30, true, FG);

    private final JTextArea transcript =
            new JTextArea();

    private final JProgressBar meter =
            new JProgressBar(0, 100);

    private final JComboBox<Tab> target =
            new JComboBox<>();

    private final JCheckBox autoSend =
            new JCheckBox(
                    "Отправлять после распознавания",
                    true
            );

    private final GreenToggle enabledButton =
            new GreenToggle();

    private final Preferences prefs =
            Preferences.userNodeForPackage(Main.class);

    private volatile String speakerName =
            prefs.get("speaker", "");

    private volatile int volume =
            Math.max(
                    0,
                    Math.min(
                            100,
                            prefs.getInt("volume", 70)
                    )
            );

    private volatile boolean useWhisper =
            prefs.getBoolean("useWhisper", true);

    private volatile boolean saveLogs =
            prefs.getBoolean("saveLogs", true);

    private volatile String snomUser = "";
    private volatile String snomPassword = "";

    private volatile boolean enabled = true;
    private volatile boolean closing;
    private volatile boolean recording;
    private volatile boolean autoSubmit = true;

    private volatile long callStarted;
    private volatile long frozenDuration;
    private volatile String lastText = "";

    private volatile TargetDataLine inputLine;
    private volatile Process whisperProcess;

    private final BlockingQueue<String> phoneEvents =
            new LinkedBlockingQueue<>();

    private final ConcurrentMap<Long, Tab> tabs =
            new ConcurrentHashMap<>();

    private final AtomicLong ids =
            new AtomicLong(1);

    private final AtomicLong generation =
            new AtomicLong();

    private final AtomicInteger processing =
            new AtomicInteger();

    private final ExecutorService speechWorker =
            Executors.newSingleThreadExecutor(
                    r -> daemon(r, "whisper-requests")
            );

    private final ExecutorService dialWorker =
            Executors.newSingleThreadExecutor(
                    r -> daemon(r, "snom-dial")
            );

    private final HttpClient http =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .build();

    private volatile BrowserJob queuedJob;

    private volatile long selectedTab = -1;
    private volatile long pendingId = -1;
    private volatile long pendingTab = -1;
    private volatile long pendingSince;

    private boolean editingTabs;

    private volatile boolean active;
    private boolean finishing;
    private boolean testCall;
    private boolean sipConnected;

    private long finishAt;
    private long lastStopAt;

    private Path wavPath;
    private WaveWriter wav;
    private boolean keepThisCall;

    private final StringBuilder spoken =
            new StringBuilder();

    private HttpServer phoneServer;
    private HttpServer chromeServer;
    private DatagramSocket sipSocket;
    private javax.swing.Timer uiTimer;

    private record Tab(
            long id,
            long window,
            String title,
            long updated
    ) {
        @Override
        public String toString() {
            return title + " · окно " + window;
        }
    }

    private record BrowserJob(
            long id,
            long tabId,
            String text
    ) {}

    private record Capture(
            TargetDataLine line,
            AudioFormat format,
            String name
    ) {}

    private record OutputDevice(
            String name,
            Mixer.Info info
    ) {
        @Override
        public String toString() {
            return name;
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(
                () -> new Main().start()
        );
    }

    private static Thread daemon(
            Runnable task,
            String name
    ) {
        Thread t = new Thread(task, name);
        t.setDaemon(true);
        return t;
    }

    private static void ui(Runnable r) {
        SwingUtilities.invokeLater(r);
    }

    private static JPanel clearPanel() {
        JPanel p = new JPanel();
        p.setOpaque(false);
        return p;
    }

    private static JLabel label(
            String value,
            int size,
            boolean bold,
            Color color
    ) {
        JLabel l = new JLabel(value);

        l.setFont(
                new Font(
                        "Segoe UI",
                        bold ? Font.BOLD : Font.PLAIN,
                        size
                )
        );

        l.setForeground(color);
        return l;
    }

    private static class Card extends JPanel {

        private final Color a;
        private final Color b;

        Card(Color a, Color b) {
            this.a = a;
            this.b = b;
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D x = (Graphics2D) g.create();

            x.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON
            );

            x.setPaint(
                    new GradientPaint(
                            0, 0, a,
                            getWidth(), getHeight(), b
                    )
            );

            x.fillRoundRect(
                    0, 0,
                    getWidth(), getHeight(),
                    20, 20
            );

            x.dispose();
            super.paintComponent(g);
        }
    }

    private static class GreenToggle extends JToggleButton {

        GreenToggle() {
            super("ON · Телефон активен");

            setSelected(true);
            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);

            setPreferredSize(
                    new Dimension(215, 43)
            );

            setFont(
                    new Font(
                            "Segoe UI",
                            Font.BOLD,
                            13
                    )
            );

            setCursor(
                    Cursor.getPredefinedCursor(
                            Cursor.HAND_CURSOR
                    )
            );
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D x = (Graphics2D) g.create();

            x.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON
            );

            Color c = isSelected()
                    ? new Color(18, 158, 79)
                    : new Color(178, 45, 55);

            if (getModel().isPressed()) {
                c = c.darker();
            }

            x.setColor(c);

            x.fillRoundRect(
                    0, 0,
                    getWidth(), getHeight(),
                    14, 14
            );

            x.setColor(Color.WHITE);
            x.setFont(getFont());

            FontMetrics fm = x.getFontMetrics();

            x.drawString(
                    getText(),
                    Math.max(
                            5,
                            (getWidth() -
                                    fm.stringWidth(getText())) / 2
                    ),
                    (getHeight() - fm.getHeight()) / 2
                            + fm.getAscent()
            );

            x.dispose();
        }
    }

    private JButton button(
            String text,
            Runnable action
    ) {
        JButton b = new JButton(text);

        b.setBackground(SECOND);
        b.setForeground(FG);
        b.setFocusPainted(false);

        b.setFont(
                new Font(
                        "Segoe UI",
                        Font.BOLD,
                        12
                )
        );

        b.setBorder(
                new EmptyBorder(10, 12, 10, 12)
        );

        b.addActionListener(e -> action.run());
        return b;
    }

    private void start() {
        frame.setDefaultCloseOperation(
                WindowConstants.DO_NOTHING_ON_CLOSE
        );

        frame.setSize(1050, 780);

        frame.setMinimumSize(
                new Dimension(820, 620)
        );

        frame.setLocationRelativeTo(null);

        frame.getContentPane().setBackground(BG);
        frame.setLayout(new BorderLayout(0, 12));

        Card header = new Card(
                new Color(20, 70, 79),
                new Color(30, 49, 101)
        );

        header.setBorder(
                new EmptyBorder(21, 24, 20, 24)
        );

        header.setLayout(new BorderLayout());

        JPanel titleBox = clearPanel();

        titleBox.setLayout(
                new BoxLayout(
                        titleBox,
                        BoxLayout.Y_AXIS
                )
        );

        titleBox.add(
                label(
                        "S N O M   /   A I   B R I D G E   5.1",
                        12, true, CYAN
                )
        );

        titleBox.add(Box.createVerticalStrut(10));

        titleBox.add(
                label(
                        "Голос в ChatGPT",
                        26, true, FG
                )
        );

        titleBox.add(Box.createVerticalStrut(8));

        titleBox.add(
                label(
                        "Подними трубку → говори → положи → Whisper → ChatGPT",
                        13, false, FG
                )
        );

        header.add(titleBox, BorderLayout.WEST);

        JPanel right = clearPanel();

        right.setLayout(
                new BoxLayout(right, BoxLayout.Y_AXIS)
        );

        timer.setAlignmentX(
                Component.RIGHT_ALIGNMENT
        );

        status.setAlignmentX(
                Component.RIGHT_ALIGNMENT
        );

        right.add(timer);
        right.add(Box.createVerticalStrut(7));
        right.add(status);

        header.add(right, BorderLayout.EAST);

        JPanel north = clearPanel();

        north.setBorder(
                new EmptyBorder(17, 20, 0, 20)
        );

        north.setLayout(new BorderLayout());
        north.add(header);

        frame.add(north, BorderLayout.NORTH);

        JPanel body = clearPanel();

        body.setBorder(
                new EmptyBorder(0, 20, 0, 20)
        );

        body.setLayout(
                new BorderLayout(0, 12)
        );

        Card info = new Card(CARD, CARD);

        info.setLayout(
                new GridLayout(4, 1, 0, 6)
        );

        info.setBorder(
                new EmptyBorder(12, 16, 12, 16)
        );

        info.add(phoneInfo);
        info.add(audioInfo);
        info.add(asrInfo);
        info.add(chromeInfo);

        body.add(info, BorderLayout.NORTH);

        Card writing = new Card(CARD, CARD);

        writing.setLayout(
                new BorderLayout(0, 8)
        );

        writing.setBorder(
                new EmptyBorder(14, 17, 13, 17)
        );

        writing.add(
                label(
                        "РАСПОЗНАННАЯ РЕЧЬ",
                        13, true, CYAN
                ),
                BorderLayout.NORTH
        );

        transcript.setFont(
                new Font(
                        "Segoe UI",
                        Font.PLAIN,
                        16
                )
        );

        transcript.setLineWrap(true);
        transcript.setWrapStyleWord(true);
        transcript.setEditable(false);

        transcript.setBackground(CARD);
        transcript.setForeground(FG);
        transcript.setCaretColor(FG);

        transcript.setText(
                "Готов к диктовке. Сними трубку Snom 320.\n"
        );

        JScrollPane scroll =
                new JScrollPane(transcript);

        scroll.setBorder(null);
        scroll.getViewport().setBackground(CARD);

        writing.add(
                scroll,
                BorderLayout.CENTER
        );

        JPanel bottomText = clearPanel();

        bottomText.setLayout(
                new BorderLayout(0, 6)
        );

        meter.setForeground(CYAN);
        meter.setBackground(SECOND);
        meter.setBorderPainted(false);

        bottomText.add(
                preview,
                BorderLayout.NORTH
        );

        bottomText.add(
                meter,
                BorderLayout.SOUTH
        );

        writing.add(
                bottomText,
                BorderLayout.SOUTH
        );

        body.add(
                writing,
                BorderLayout.CENTER
        );

        frame.add(
                body,
                BorderLayout.CENTER
        );

        Card controls = new Card(CARD, CARD);

        controls.setLayout(
                new BorderLayout(0, 12)
        );

        controls.setBorder(
                new EmptyBorder(13, 16, 13, 16)
        );

        JPanel select = clearPanel();

        select.setLayout(
                new BorderLayout(12, 0)
        );

        select.add(
                label(
                        "ВКЛАДКА CHATGPT",
                        12, true, MUTED
                ),
                BorderLayout.WEST
        );

        target.setFont(
                new Font(
                        "Segoe UI",
                        Font.PLAIN,
                        13
                )
        );

        target.addActionListener(e -> {
            if (!editingTabs) {
                Tab t =
                        (Tab) target.getSelectedItem();

                selectedTab = t == null
                        ? -1
                        : t.id();
            }
        });

        select.add(
                target,
                BorderLayout.CENTER
        );

        controls.add(
                select,
                BorderLayout.NORTH
        );

        JPanel actions = clearPanel();

        actions.setLayout(
                new FlowLayout(
                        FlowLayout.LEFT,
                        7, 0
                )
        );

        actions.add(
                button(
                        "Тест записи",
                        () -> phoneEvents.offer("test")
                )
        );

        actions.add(
                button(
                        "Закончить",
                        () -> phoneEvents.offer("hangup")
                )
        );

        actions.add(
                button(
                        "Отправить ещё раз",
                        () -> queueBrowserJob(lastText)
                )
        );

        actions.add(
                button(
                        "Тест ChatGPT",
                        () -> queueBrowserJob(
                                "Проверка связи. Ответь одним предложением."
                        )
                )
        );

        actions.add(
                button(
                        "Сброс ожидания",
                        this::resetPending
                )
        );

        actions.add(
                button(
                        "Настройки",
                        this::openSettings
                )
        );

        controls.add(
                actions,
                BorderLayout.CENTER
        );

        JPanel toggles = clearPanel();

        toggles.setLayout(
                new FlowLayout(
                        FlowLayout.LEFT,
                        13, 0
                )
        );

        autoSend.setForeground(FG);
        autoSend.setOpaque(false);

        autoSend.addActionListener(
                e -> autoSubmit = autoSend.isSelected()
        );

        toggles.add(autoSend);

        enabledButton.addActionListener(
                e -> setEnabled(
                        enabledButton.isSelected()
                )
        );

        toggles.add(enabledButton);

        toggles.add(
                label(
                        "ON при запуске",
                        11, false, MUTED
                )
        );

        controls.add(
                toggles,
                BorderLayout.SOUTH
        );

        JPanel south = clearPanel();

        south.setLayout(new BorderLayout());

        south.setBorder(
                new EmptyBorder(0, 20, 17, 20)
        );

        south.add(controls);

        frame.add(
                south,
                BorderLayout.SOUTH
        );

        frame.addWindowListener(
                new java.awt.event.WindowAdapter() {
                    @Override
                    public void windowClosing(
                            java.awt.event.WindowEvent e
                    ) {
                        shutdown();
                    }
                }
        );

        uiTimer = new javax.swing.Timer(
                400,
                e -> {
                    long nanos = recording
                            ? System.nanoTime() - callStarted
                            : frozenDuration;

                    long seconds = Math.max(
                            0,
                            nanos / 1_000_000_000L
                    );

                    timer.setText(
                            String.format(
                                    Locale.ROOT,
                                    "%02d:%02d",
                                    seconds / 60,
                                    seconds % 60
                            )
                    );

                    refreshTabs();

                    if (
                            pendingId != -1 &&
                                    System.currentTimeMillis()
                                            - pendingSince > 630000
                    ) {
                        resetPending();

                        chromeInfo.setText(
                                "ChatGPT: превышено время ожидания ответа"
                        );
                    }
                }
        );

        uiTimer.start();
        frame.setVisible(true);

        startPhoneServer();
        startSipServer();
        startChromeServer();

        Runtime.getRuntime().addShutdownHook(
                new Thread(
                        this::stopWhisperAutomatically,
                        "whisper-shutdown"
                )
        );

        daemon(
                this::startWhisperAutomatically,
                "whisper-startup"
        ).start();

        daemon(
                this::capture,
                "vosk-capture"
        ).start();
    }

    private void setEnabled(boolean on) {
        enabled = on;

        enabledButton.setText(
                on
                        ? "ON · Телефон активен"
                        : "OFF · Телефон отключён"
        );

        enabledButton.repaint();

        status.setText(
                on
                        ? "Готов · сними трубку"
                        : "Отключён · OFF"
        );

        phoneEvents.clear();

        if (!on) {
            generation.incrementAndGet();

            phoneEvents.offer("cancel");
            resetPending();

            chromeInfo.setText(
                    "OFF: новая диктовка и отправка заблокированы"
            );
        }
    }

    private void refreshTabs() {
        long now = System.currentTimeMillis();

        List<Tab> list = tabs.values()
                .stream()
                .filter(
                        t -> now - t.updated() < 15000
                )
                .sorted(
                        Comparator.comparing(Tab::title)
                                .thenComparingLong(Tab::id)
                )
                .toList();

        boolean changed =
                list.size() != target.getItemCount();

        if (!changed) {
            for (int i = 0; i < list.size(); i++) {
                Tab a = list.get(i);
                Tab b = target.getItemAt(i);

                if (
                        a.id() != b.id() ||
                                a.window() != b.window() ||
                                !a.title().equals(b.title())
                ) {
                    changed = true;
                    break;
                }
            }
        }

        if (!changed) {
            return;
        }

        long old = selectedTab;
        editingTabs = true;
        target.removeAllItems();

        int index = -1;

        for (int i = 0; i < list.size(); i++) {
            target.addItem(list.get(i));

            if (list.get(i).id() == old) {
                index = i;
            }
        }

        if (index < 0 && !list.isEmpty()) {
            index = 0;
        }

        target.setSelectedIndex(index);

        selectedTab = index < 0
                ? -1
                : list.get(index).id();

        editingTabs = false;

        if (
                list.isEmpty() &&
                        pendingId == -1
        ) {
            chromeInfo.setText(
                    "Chrome: открой ChatGPT и обнови F5"
            );

        } else if (
                !list.isEmpty() &&
                        pendingId == -1
        ) {
            chromeInfo.setText(
                    "Chrome: подключено вкладок " +
                            list.size()
            );
        }
    }

    private void startPhoneServer() {
        try {
            phoneServer = HttpServer.create(
                    new InetSocketAddress(
                            PC,
                            PHONE_PORT
                    ),
                    0
            );

            phoneServer.createContext("/", x -> {
                String remote =
                        x.getRemoteAddress()
                                .getAddress()
                                .getHostAddress();

                String ev = switch (
                        x.getRequestURI().getPath()
                        ) {
                    case "/offhook" -> "offhook";

                    case "/connected", "/outgoing" ->
                            "connected";

                    case "/hangup", "/disconnected" ->
                            "hangup";

                    default -> "";
                };

                int code =
                        !"GET".equals(x.getRequestMethod()) ||
                                !(
                                        remote.equals(SNOM) ||
                                                remote.equals(PC)
                                )
                                ? 403
                                : ev.isEmpty() ? 404 : 200;

                byte[] b = (
                        code == 200
                                ? "OK"
                                : "Not found"
                ).getBytes(StandardCharsets.UTF_8);

                x.sendResponseHeaders(
                        code,
                        b.length
                );

                try (
                        var out = x.getResponseBody()
                ) {
                    out.write(b);
                }

                if (code == 200 && enabled) {
                    phoneEvents.offer(ev);
                }
            });

            phoneServer.setExecutor(
                    Executors.newCachedThreadPool(
                            r -> daemon(
                                    r,
                                    "snom-http"
                            )
                    )
            );

            phoneServer.start();

            phoneInfo.setText(
                    "Snom HTTP: " +
                            PC + ":" +
                            PHONE_PORT + " готов"
            );

        } catch (IOException ex) {
            phoneInfo.setText(
                    "Snom: ошибка HTTP: " +
                            ex.getMessage()
            );
        }
    }

    private void startSipServer() {
        try {
            sipSocket = new DatagramSocket(
                    new InetSocketAddress(
                            "127.0.0.1",
                            MICROSIP_PORT
                    )
            );

            daemon(() -> {
                byte[] buffer = new byte[1024];

                while (!closing) {
                    try {
                        DatagramPacket packet =
                                new DatagramPacket(
                                        buffer,
                                        buffer.length
                                );

                        sipSocket.receive(packet);

                        String ev = new String(
                                packet.getData(),
                                packet.getOffset(),
                                packet.getLength(),
                                StandardCharsets.UTF_8
                        ).split("\\|", 2)[0]
                                .trim()
                                .toLowerCase(Locale.ROOT);

                        if (
                                enabled &&
                                        ev.equals("connected")
                        ) {
                            phoneEvents.offer("connected");
                        }

                        if (
                                enabled &&
                                        ev.equals("ended")
                        ) {
                            phoneEvents.offer("hangup");
                        }

                    } catch (Exception ignored) {
                    }
                }

            }, "sip-events").start();

        } catch (SocketException ex) {
            phoneInfo.setText(
                    "MicroSIP UDP " +
                            MICROSIP_PORT + " занят"
            );
        }
    }

    private void startChromeServer() {
        try {
            chromeServer = HttpServer.create(
                    new InetSocketAddress(
                            "127.0.0.1",
                            CHROME_PORT
                    ),
                    0
            );

            chromeServer.createContext("/poll", x -> {
                if (!chromeAuthorized(x)) {
                    return;
                }

                try {
                    JsonNode data = JSON.readTree(
                            x.getRequestBody()
                    );

                    long tab =
                            data.path("tabId").asLong(-1);

                    long window =
                            data.path("windowId").asLong(-1);

                    String url =
                            data.path("url").asText("");

                    if (
                            tab < 0 ||
                                    !(
                                            url.startsWith(
                                                    "https://chatgpt.com/"
                                            ) ||
                                                    url.startsWith(
                                                            "https://chat.openai.com/"
                                                    )
                                    )
                    ) {
                        respond(
                                x,
                                400,
                                JSON.createObjectNode()
                        );

                        return;
                    }

                    tabs.put(
                            tab,
                            new Tab(
                                    tab,
                                    window,
                                    data.path("title")
                                            .asText("ChatGPT"),
                                    System.currentTimeMillis()
                            )
                    );

                    ObjectNode response =
                            JSON.createObjectNode();

                    BrowserJob job = queuedJob;

                    if (
                            enabled &&
                                    job != null &&
                                    job.tabId() == tab &&
                                    !data.path("busy")
                                            .asBoolean(false)
                    ) {
                        synchronized (this) {
                            if (queuedJob == job) {
                                queuedJob = null;

                                ObjectNode j =
                                        response.putObject(
                                                "job"
                                        );

                                j.put("id", job.id());
                                j.put("text", job.text());
                            }
                        }
                    }

                    respond(x, 200, response);

                } catch (Exception ex) {
                    safeError(x);
                }
            });

            chromeServer.createContext("/event", x -> {
                if (!chromeAuthorized(x)) {
                    return;
                }

                try {
                    JsonNode d = JSON.readTree(
                            x.getRequestBody()
                    );

                    long id =
                            d.path("jobId").asLong(-1);

                    long tab =
                            d.path("tabId").asLong(-1);

                    String kind =
                            d.path("kind").asText("");

                    String detail =
                            d.path("message").asText("");

                    if (
                            id == pendingId &&
                                    tab == pendingTab
                    ) {
                        if (
                                kind.equals("done") ||
                                        kind.equals("error")
                        ) {
                            pendingId = -1;
                            pendingTab = -1;
                            pendingSince = 0;
                        }

                        ui(() -> {
                            switch (kind) {
                                case "sent" ->
                                        chromeInfo.setText(
                                                "ChatGPT: запрос отправлен"
                                        );

                                case "progress" ->
                                        chromeInfo.setText(
                                                "ChatGPT: " + detail
                                        );

                                case "error" ->
                                        chromeInfo.setText(
                                                "Chrome: ошибка: " + detail
                                        );

                                case "done" -> {
                                    chromeInfo.setText(
                                            "ChatGPT: ответ завершён"
                                    );

                                    playSignal();
                                }
                            }
                        });
                    }

                    respond(
                            x,
                            200,
                            JSON.createObjectNode()
                                    .put("ok", true)
                    );

                } catch (Exception ex) {
                    safeError(x);
                }
            });

            chromeServer.setExecutor(
                    Executors.newCachedThreadPool(
                            r -> daemon(
                                    r,
                                    "chrome-http"
                            )
                    )
            );

            chromeServer.start();

        } catch (IOException ex) {
            chromeInfo.setText(
                    "Chrome: порт занят: " +
                            ex.getMessage()
            );
        }
    }

    private boolean chromeAuthorized(
            HttpExchange x
    ) throws IOException {
        if (
                !"POST".equals(x.getRequestMethod()) ||
                        !"chrome-ext-v1".equals(
                                x.getRequestHeaders()
                                        .getFirst("X-Snom-Bridge")
                        )
        ) {
            respond(
                    x,
                    403,
                    JSON.createObjectNode()
            );

            return false;
        }

        return true;
    }

    private static void respond(
            HttpExchange x,
            int code,
            JsonNode data
    ) throws IOException {
        byte[] bytes =
                JSON.writeValueAsBytes(data);

        x.getResponseHeaders().set(
                "Content-Type",
                "application/json; charset=utf-8"
        );

        x.sendResponseHeaders(
                code,
                bytes.length
        );

        try (
                var out = x.getResponseBody()
        ) {
            out.write(bytes);
        }
    }

    private static void safeError(
            HttpExchange x
    ) {
        try {
            respond(
                    x,
                    500,
                    JSON.createObjectNode()
            );

        } catch (IOException ignored) {
        }
    }

    private void queueBrowserJob(String text) {
        if (!enabled) {
            chromeInfo.setText(
                    "OFF: отправка заблокирована"
            );
            return;
        }

        if (pendingId != -1) {
            chromeInfo.setText(
                    "Предыдущий запрос ещё обрабатывается"
            );
            return;
        }

        if (
                text == null ||
                        text.isBlank()
        ) {
            chromeInfo.setText(
                    "Нет текста для отправки"
            );
            return;
        }

        Tab tab = tabs.get(selectedTab);

        if (
                tab == null ||
                        System.currentTimeMillis() -
                                tab.updated() > 15000
        ) {
            chromeInfo.setText(
                    "Выбери работающую вкладку ChatGPT"
            );
            return;
        }

        BrowserJob job = new BrowserJob(
                ids.getAndIncrement(),
                tab.id(),
                text
        );

        queuedJob = job;

        pendingId = job.id();
        pendingTab = tab.id();
        pendingSince = System.currentTimeMillis();

        chromeInfo.setText(
                "Chrome: отправляю готовый текст..."
        );
    }

    private void resetPending() {
        queuedJob = null;
        pendingId = -1;
        pendingTab = -1;
        pendingSince = 0;

        chromeInfo.setText(
                "Ожидание Java сброшено (отправленное не отменено)"
        );
    }

    private void dial101() {
        if (!enabled) {
            return;
        }

        String username = snomUser;
        String password = snomPassword;

        dialWorker.execute(() -> {
            try {
                if (!enabled) {
                    return;
                }

                HttpClient.Builder clientBuilder =
                        HttpClient.newBuilder()
                                .connectTimeout(
                                        Duration.ofSeconds(3)
                                );

                if (!username.isBlank()) {
                    clientBuilder.authenticator(
                            new Authenticator() {
                                @Override
                                protected PasswordAuthentication
                                getPasswordAuthentication() {
                                    return new PasswordAuthentication(
                                            username,
                                            password.toCharArray()
                                    );
                                }
                            }
                    );
                }

                URI uri = URI.create(
                        "http://" + SNOM +
                                "/index.htm?number=101&outgoing_uri=100%40192.168.50.1"
                );

                HttpRequest req =
                        HttpRequest.newBuilder(uri)
                                .timeout(
                                        Duration.ofSeconds(5)
                                )
                                .GET()
                                .build();

                int code = clientBuilder
                        .build()
                        .send(
                                req,
                                HttpResponse.BodyHandlers
                                        .discarding()
                        )
                        .statusCode();

                ui(() -> phoneInfo.setText(
                        code < 400
                                ? "Snom: команда набора 101 отправлена"
                                : "Snom HTTP: " +
                                code +
                                " · проверь логин/пароль"
                ));

            } catch (Exception ex) {
                ui(() -> phoneInfo.setText(
                        "Набор 101 не удался: " +
                                ex.getMessage()
                ));
            }
        });
    }

    private void capture() {
        if (
                !Files.isDirectory(
                        Path.of("model/am")
                )
        ) {
            ui(() -> audioInfo.setText(
                    "Нет модели Vosk в папке model"
            ));
            return;
        }

        try {
            LibVosk.setLogLevel(
                    LogLevel.WARNINGS
            );

            try (
                    Model model = new Model("model");

                    Recognizer recognizer =
                            new Recognizer(
                                    model,
                                    SAMPLE_RATE
                            )
            ) {
                Capture source = findCable();

                TargetDataLine line =
                        source.line();

                inputLine = line;
                line.start();

                Downsampler sampler = new Downsampler(
                        source.format().getSampleRate(),
                        source.format().getChannels()
                );

                byte[] input = new byte[8192];
                byte[] pcm = new byte[16384];

                int frameSize =
                        source.format().getFrameSize();

                ui(() -> audioInfo.setText(
                        "Vosk готов · " +
                                source.name() +
                                " · " +
                                (int) source.format()
                                        .getSampleRate() +
                                " Гц"
                ));

                try {
                    while (!closing) {
                        String ev;

                        while (
                                (ev = phoneEvents.poll()) != null
                        ) {
                            if (ev.equals("cancel")) {
                                cancelCall(
                                        recognizer,
                                        sampler
                                );
                                continue;
                            }

                            if (!enabled) {
                                continue;
                            }

                            if (
                                    ev.equals("offhook") ||
                                            ev.equals("connected") ||
                                            ev.equals("test")
                            ) {
                                if (
                                        !active &&
                                                (
                                                        ev.equals("offhook") ||
                                                                System.nanoTime() -
                                                                        lastStopAt >
                                                                        1_500_000_000L
                                                )
                                ) {
                                    startCall(
                                            recognizer,
                                            sampler,
                                            line,
                                            ev.equals("test")
                                    );

                                    if (
                                            ev.equals("offhook")
                                    ) {
                                        dial101();
                                    }

                                } else if (
                                        ev.equals("connected") &&
                                                active
                                ) {
                                    sipConnected = true;

                                    ui(() -> phoneInfo.setText(
                                            "MicroSIP: вызов соединён"
                                    ));
                                }

                            } else if (
                                    ev.equals("hangup") &&
                                            active &&
                                            !finishing
                            ) {
                                finishing = true;
                                recording = false;

                                frozenDuration =
                                        System.nanoTime() -
                                                callStarted;

                                finishAt =
                                        System.nanoTime() +
                                                500_000_000L;

                                ui(() -> status.setText(
                                        "Трубка положена · завершаю запись..."
                                ));
                            }
                        }

                        if (
                                active &&
                                        finishing &&
                                        System.nanoTime() >= finishAt
                        ) {
                            finishCall(recognizer);

                            sampler.reset();
                            recognizer.reset();
                        }

                        int n = Math.min(
                                line.available(),
                                input.length
                        );

                        n -= n % frameSize;

                        if (n < frameSize) {
                            Thread.sleep(15);
                            continue;
                        }

                        int got = line.read(
                                input,
                                0,
                                n
                        );

                        if (
                                got <= 0 ||
                                        !active
                        ) {
                            continue;
                        }

                        int bytes = sampler.convert(
                                input,
                                got,
                                pcm
                        );

                        if (bytes <= 0) {
                            continue;
                        }

                        if (wav != null) {
                            wav.write(
                                    pcm,
                                    0,
                                    bytes
                            );
                        }

                        if (
                                recognizer.acceptWaveForm(
                                        pcm,
                                        bytes
                                )
                        ) {
                            appendVosk(
                                    recognizer.getResult()
                            );

                            ui(() -> preview.setText(
                                    "Слушаю..."
                            ));

                        } else {
                            String phrase = field(
                                    recognizer.getPartialResult(),
                                    "partial"
                            );

                            ui(() -> preview.setText(
                                    phrase.isBlank()
                                            ? "Слушаю..."
                                            : "Слышу: " + phrase
                            ));
                        }

                        int level = audioLevel(
                                pcm,
                                bytes
                        );

                        ui(() -> meter.setValue(level));
                    }

                } finally {
                    line.close();
                }
            }

        } catch (Exception ex) {
            if (!closing) {
                ui(() -> audioInfo.setText(
                        "Ошибка Vosk/VB-CABLE: " +
                                ex.getMessage()
                ));
            }

        } finally {
            if (wav != null) {
                try {
                    wav.close();
                } catch (IOException ignored) {
                }

                wav = null;
            }

            if (
                    !keepThisCall &&
                            wavPath != null
            ) {
                try {
                    Files.deleteIfExists(wavPath);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void startCall(
            Recognizer rec,
            Downsampler sampler,
            TargetDataLine line,
            boolean test
    ) {
        if (!enabled) {
            return;
        }

        active = true;
        finishing = false;
        testCall = test;
        sipConnected = false;
        recording = true;

        callStarted = System.nanoTime();
        frozenDuration = 0;

        spoken.setLength(0);

        sampler.reset();
        rec.reset();
        line.flush();

        keepThisCall = saveLogs;

        try {
            if (keepThisCall) {
                Files.createDirectories(HISTORY);

                wavPath = HISTORY.resolve(
                        STAMP.format(
                                LocalDateTime.now()
                        ) + ".wav"
                );

            } else {
                wavPath = Files.createTempFile(
                        "snom_bridge_",
                        ".wav"
                );
            }

            wav = new WaveWriter(wavPath);

        } catch (IOException ex) {
            wav = null;
            wavPath = null;

            ui(() -> asrInfo.setText(
                    "Не удалось создать WAV: " +
                            ex.getMessage()
            ));
        }

        ui(() -> {
            transcript.setText("");
            meter.setValue(0);

            status.setText(
                    test
                            ? "Тест диктовки"
                            : "Трубка поднята · слушаю"
            );

            preview.setText("Слушаю...");

            asrInfo.setText(
                    useWhisper
                            ? "Whisper: запишу звонок для уточнения"
                            : "Vosk: основной режим"
            );
        });
    }

    private void cancelCall(
            Recognizer rec,
            Downsampler sampler
    ) {
        active = false;
        finishing = false;
        recording = false;
        sipConnected = false;

        lastStopAt = System.nanoTime();

        spoken.setLength(0);

        rec.reset();
        sampler.reset();

        if (wav != null) {
            try {
                wav.close();
            } catch (IOException ignored) {
            }
        }

        wav = null;

        if (
                !keepThisCall &&
                        wavPath != null
        ) {
            try {
                Files.deleteIfExists(wavPath);
            } catch (IOException ignored) {
            }
        }

        wavPath = null;

        ui(() -> {
            meter.setValue(0);
            preview.setText(" ");
            status.setText("Отключён · OFF");
        });
    }

    private void appendVosk(String json) {
        String phrase = field(json, "text");

        if (phrase.isBlank()) {
            return;
        }

        if (!spoken.isEmpty()) {
            spoken.append(' ');
        }

        spoken.append(phrase);

        String line =
                CLOCK.format(LocalDateTime.now()) +
                        " | " + phrase;

        ui(() -> {
            transcript.append(
                    line + "\n\n"
            );

            transcript.setCaretPosition(
                    transcript.getDocument().getLength()
            );
        });
    }

    private void finishCall(Recognizer rec) {
        appendVosk(
                rec.getFinalResult()
        );

        active = false;
        finishing = false;
        recording = false;

        lastStopAt = System.nanoTime();

        String draft =
                spoken.toString().trim();

        Path recordPath = wavPath;
        boolean retain = keepThisCall;

        long bytes = 0;

        if (wav != null) {
            bytes = wav.length();

            try {
                wav.close();
            } catch (IOException ignored) {
            }
        }

        wav = null;
        wavPath = null;

        boolean wasTest = testCall;
        long token = generation.get();

        boolean runWhisper =
                useWhisper &&
                        bytes >= 6400 &&
                        recordPath != null;

        processing.incrementAndGet();

        ui(() -> {
            status.setText(
                    "Запись готова · ожидаю следующего звонка"
            );

            meter.setValue(0);
            preview.setText(" ");

            asrInfo.setText(
                    runWhisper
                            ? "Whisper: уточняю русский и английский..."
                            : "Vosk: итоговая расшифровка"
            );
        });

        speechWorker.submit(() -> {
            String result = draft;
            String error = "";

            try {
                if (runWhisper) {
                    try {
                        result = callWhisper(recordPath);

                    } catch (Exception ex) {
                        error = ex.getMessage();
                        result = draft;
                    }
                }

                if (
                        retain &&
                                recordPath != null
                ) {
                    try {
                        Files.createDirectories(HISTORY);

                        Path txt = HISTORY.resolve(
                                recordPath.getFileName()
                                        .toString()
                                        .replaceFirst(
                                                "\\.wav$",
                                                ".txt"
                                        )
                        );

                        Files.writeString(
                                txt,
                                "Vosk:\n" + draft +
                                        "\n\nИтог:\n" + result,
                                StandardCharsets.UTF_8
                        );

                    } catch (IOException ex) {
                        System.err.println(
                                "Не удалось сохранить историю: " +
                                        ex
                        );
                    }
                }

                if (
                        !enabled ||
                                generation.get() != token
                ) {
                    return;
                }

                String finalText = result;
                String issue = error;

                ui(() -> {
                    if (
                            !enabled ||
                                    generation.get() != token
                    ) {
                        return;
                    }

                    lastText = finalText;

                    if (!finalText.isBlank()) {
                        transcript.setText(
                                "ИТОГ:\n" + finalText +
                                        "\n\n---\nVosk (черновик):\n" +
                                        draft
                        );

                        try {
                            Toolkit.getDefaultToolkit()
                                    .getSystemClipboard()
                                    .setContents(
                                            new StringSelection(
                                                    finalText
                                            ),
                                            null
                                    );

                        } catch (
                                IllegalStateException ignored
                        ) {
                        }

                        if (
                                autoSubmit &&
                                        !wasTest
                        ) {
                            queueBrowserJob(finalText);
                        }
                    }

                    asrInfo.setText(
                            !issue.isBlank()
                                    ? "Whisper недоступен, оставил Vosk: " +
                                    issue
                                    : runWhisper
                                    ? "Whisper: расшифровка готова"
                                    : "Vosk: текст готов"
                    );
                });

            } finally {
                if (
                        !retain &&
                                recordPath != null
                ) {
                    try {
                        Files.deleteIfExists(recordPath);

                    } catch (IOException ex) {
                        System.err.println(
                                "Не удалён временный WAV: " +
                                        ex
                        );
                    }
                }

                processing.decrementAndGet();
            }
        });
    }

    private String callWhisper(
            Path file
    ) throws Exception {
        byte[] body =
                Files.readAllBytes(file);

        HttpRequest request =
                HttpRequest.newBuilder(
                                URI.create(
                                        "http://127.0.0.1:" +
                                                WHISPER_PORT +
                                                "/transcribe"
                                )
                        )
                        .header(
                                "Content-Type",
                                "audio/wav"
                        )
                        .timeout(
                                Duration.ofMinutes(8)
                        )
                        .POST(
                                HttpRequest.BodyPublishers
                                        .ofByteArray(body)
                        )
                        .build();

        HttpResponse<String> response =
                http.send(
                        request,
                        HttpResponse.BodyHandlers
                                .ofString(
                                        StandardCharsets.UTF_8
                                )
                );

        if (response.statusCode() != 200) {
            throw new IOException(
                    "HTTP " +
                            response.statusCode() +
                            ": " + response.body()
            );
        }

        String result = field(
                response.body(),
                "text"
        );

        if (result.isBlank()) {
            throw new IOException(
                    "Whisper вернул пустой текст"
            );
        }

        return result;
    }

    private static String field(
            String json,
            String key
    ) {
        try {
            return JSON.readTree(json)
                    .path(key)
                    .asText("")
                    .trim();

        } catch (Exception ex) {
            return "";
        }
    }

    private static Capture findCable()
            throws LineUnavailableException {
        AudioFormat[] formats = {
                new AudioFormat(
                        48000, 16, 2, true, false
                ),
                new AudioFormat(
                        48000, 16, 1, true, false
                ),
                new AudioFormat(
                        44100, 16, 2, true, false
                ),
                new AudioFormat(
                        16000, 16, 1, true, false
                )
        };

        for (
                Mixer.Info m :
                AudioSystem.getMixerInfo()
        ) {
            String name = m.getName()
                    .toLowerCase(Locale.ROOT);

            if (
                    !(
                            name.contains("cable output") ||
                                    name.contains(
                                            "vb-audio virtual cable"
                                    )
                    )
            ) {
                continue;
            }

            for (AudioFormat f : formats) {
                try {
                    TargetDataLine line =
                            AudioSystem.getTargetDataLine(
                                    f,
                                    m
                            );

                    line.open(f, 16384);

                    return new Capture(
                            line,
                            f,
                            m.getName()
                    );

                } catch (Exception ignored) {
                }
            }
        }

        throw new LineUnavailableException(
                "CABLE Output не найден в устройствах записи"
        );
    }

    private static int audioLevel(
            byte[] src,
            int length
    ) {
        double sum = 0;
        int count = 0;

        for (
                int i = 0;
                i + 1 < length;
                i += 2
        ) {
            short v = (short) (
                    (src[i] & 255) |
                            (src[i + 1] << 8)
            );

            sum += (double) v * v;
            count++;
        }

        return Math.min(
                100,
                (int) (
                        Math.sqrt(
                                sum / Math.max(count, 1)
                        ) / 110
                )
        );
    }

    private static class Downsampler {

        private final int channels;
        private final double ratio;

        private double remaining;
        private double sum;

        Downsampler(
                float sourceRate,
                int channels
        ) {
            this.channels = channels;
            this.ratio = sourceRate / SAMPLE_RATE;
            reset();
        }

        void reset() {
            remaining = ratio;
            sum = 0;
        }

        int convert(
                byte[] src,
                int length,
                byte[] dest
        ) {
            int pos = 0;
            int frame = channels * 2;

            for (
                    int i = 0;
                    i + frame <= length;
                    i += frame
            ) {
                double value = 0;

                for (
                        int c = 0;
                        c < channels;
                        c++
                ) {
                    int p = i + 2 * c;

                    value += (short) (
                            (src[p] & 255) |
                                    (src[p + 1] << 8)
                    );
                }

                value /= channels;

                double amount = 1;

                while (amount > 1e-9) {
                    double part = Math.min(
                            amount,
                            remaining
                    );

                    sum += value * part;
                    amount -= part;
                    remaining -= part;

                    if (remaining < 1e-9) {
                        int v = Math.max(
                                -32768,
                                Math.min(
                                        32767,
                                        (int) Math.round(
                                                sum / ratio
                                        )
                                )
                        );

                        dest[pos++] = (byte) v;
                        dest[pos++] =
                                (byte) (v >>> 8);

                        remaining = ratio;
                        sum = 0;
                    }
                }
            }

            return pos;
        }
    }

    private static class WaveWriter
            implements AutoCloseable {

        private final RandomAccessFile file;
        private long bytes;

        WaveWriter(Path path) throws IOException {
            Files.createDirectories(
                    path.getParent()
            );

            file = new RandomAccessFile(
                    path.toFile(),
                    "rw"
            );

            file.setLength(0);
            file.write(new byte[44]);
        }

        void write(
                byte[] data,
                int offset,
                int n
        ) throws IOException {
            file.write(data, offset, n);
            bytes += n;
        }

        long length() {
            return bytes;
        }

        private void little32(long n)
                throws IOException {
            file.write((int) n & 255);
            file.write((int) (n >> 8) & 255);
            file.write((int) (n >> 16) & 255);
            file.write((int) (n >> 24) & 255);
        }

        private void little16(int n)
                throws IOException {
            file.write(n & 255);
            file.write((n >> 8) & 255);
        }

        @Override
        public void close() throws IOException {
            file.seek(0);

            file.writeBytes("RIFF");
            little32(36 + bytes);

            file.writeBytes("WAVEfmt ");
            little32(16);

            little16(1);
            little16(1);
            little32(SAMPLE_RATE);

            little32(SAMPLE_RATE * 2);
            little16(2);
            little16(16);

            file.writeBytes("data");
            little32(bytes);

            file.close();
        }
    }

    private List<OutputDevice> outputs() {
        List<OutputDevice> result =
                new ArrayList<>();

        result.add(
                new OutputDevice(
                        "По умолчанию Windows",
                        null
                )
        );

        AudioFormat probe = new AudioFormat(
                44100, 16, 1, true, false
        );

        for (
                Mixer.Info info :
                AudioSystem.getMixerInfo()
        ) {
            if (
                    AudioSystem.getMixer(info)
                            .isLineSupported(
                                    new DataLine.Info(
                                            SourceDataLine.class,
                                            probe
                                    )
                            )
            ) {
                result.add(
                        new OutputDevice(
                                info.getName(),
                                info
                        )
                );
            }
        }

        return result;
    }

    private void openSettings() {
        JDialog dialog = new JDialog(
                frame,
                "Настройки Snom AI Bridge",
                true
        );

        dialog.setSize(680, 480);

        dialog.setMinimumSize(
                new Dimension(600, 450)
        );

        dialog.setLocationRelativeTo(frame);

        JPanel form = new JPanel(
                new GridBagLayout()
        );

        form.setBorder(
                new EmptyBorder(16, 18, 12, 18)
        );

        GridBagConstraints c =
                new GridBagConstraints();

        c.insets =
                new Insets(6, 4, 6, 8);

        c.anchor =
                GridBagConstraints.WEST;

        c.gridx = 0;
        c.gridy = 0;

        form.add(
                new JLabel("Колонки для сигнала:"),
                c
        );

        JComboBox<OutputDevice> devices =
                new JComboBox<>(
                        outputs().toArray(
                                new OutputDevice[0]
                        )
                );

        for (
                int i = 0;
                i < devices.getItemCount();
                i++
        ) {
            OutputDevice d =
                    devices.getItemAt(i);

            if (
                    (
                            d.info() == null &&
                                    speakerName.isBlank()
                    ) ||
                            (
                                    d.info() != null &&
                                            d.name().equals(speakerName)
                            )
            ) {
                devices.setSelectedIndex(i);
                break;
            }
        }

        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;

        form.add(devices, c);

        c.gridx = 0;
        c.gridy++;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;

        form.add(
                new JLabel("Громкость сигнала:"),
                c
        );

        JSlider volumeSlider = new JSlider(
                0, 100, volume
        );

        JLabel percent =
                new JLabel(volume + "%");

        volumeSlider.addChangeListener(
                e -> percent.setText(
                        volumeSlider.getValue() + "%"
                )
        );

        JPanel sliderRow =
                new JPanel(new BorderLayout(10, 0));

        sliderRow.add(
                volumeSlider,
                BorderLayout.CENTER
        );

        sliderRow.add(
                percent,
                BorderLayout.EAST
        );

        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;

        form.add(sliderRow, c);

        c.gridx = 0;
        c.gridy++;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;

        form.add(
                new JLabel("Распознавание:"),
                c
        );

        JCheckBox whisperCheck =
                new JCheckBox(
                        "Уточнять через GPU Whisper",
                        useWhisper
                );

        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;

        form.add(whisperCheck, c);

        c.gridx = 0;
        c.gridy++;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;

        form.add(
                new JLabel("История звонков:"),
                c
        );

        JCheckBox logCheck =
                new JCheckBox(
                        "Сохранять логи (WAV и TXT)",
                        saveLogs
                );

        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;

        form.add(logCheck, c);

        c.gridx = 0;
        c.gridy++;
        c.gridwidth = 2;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;

        JLabel hint = new JLabel(
                "Выключение записи применяется со следующего звонка."
        );

        hint.setForeground(
                new Color(90, 90, 90)
        );

        form.add(hint, c);

        c.gridy++;

        JPanel logActions = new JPanel(
                new FlowLayout(
                        FlowLayout.LEFT,
                        9, 0
                )
        );

        JButton openFolder =
                new JButton("Открыть папку логов");

        openFolder.addActionListener(
                e -> openLogFolder()
        );

        JButton clearLogs =
                new JButton("Очистить все логи");

        clearLogs.addActionListener(
                e -> clearAllLogs(dialog)
        );

        logActions.add(openFolder);
        logActions.add(clearLogs);

        form.add(logActions, c);

        c.gridwidth = 1;
        c.gridx = 0;
        c.gridy++;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;

        form.add(
                new JLabel("HTTP пользователь Snom:"),
                c
        );

        JTextField user = new JTextField(
                snomUser,
                16
        );

        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;

        form.add(user, c);

        c.gridx = 0;
        c.gridy++;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;

        form.add(
                new JLabel("HTTP пароль Snom:"),
                c
        );

        JPasswordField pass =
                new JPasswordField(
                        snomPassword,
                        16
                );

        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;

        form.add(pass, c);

        JPanel footer = new JPanel(
                new FlowLayout(
                        FlowLayout.RIGHT
                )
        );

        JButton testSound =
                new JButton("Проверить звук");

        testSound.addActionListener(e -> {
            OutputDevice dev =
                    (OutputDevice) devices.getSelectedItem();

            playSignalOn(
                    dev == null
                            ? null
                            : dev.info(),
                    volumeSlider.getValue()
            );
        });

        JButton cancel =
                new JButton("Отмена");

        cancel.addActionListener(
                e -> dialog.dispose()
        );

        JButton save =
                new JButton("Сохранить");

        save.addActionListener(e -> {
            OutputDevice dev =
                    (OutputDevice) devices.getSelectedItem();

            speakerName =
                    dev == null ||
                            dev.info() == null
                            ? ""
                            : dev.name();

            volume = volumeSlider.getValue();

            useWhisper =
                    whisperCheck.isSelected();

            saveLogs =
                    logCheck.isSelected();

            snomUser =
                    user.getText().trim();

            snomPassword =
                    new String(
                            pass.getPassword()
                    );

            prefs.put(
                    "speaker",
                    speakerName
            );

            prefs.putInt(
                    "volume",
                    volume
            );

            prefs.putBoolean(
                    "useWhisper",
                    useWhisper
            );

            prefs.putBoolean(
                    "saveLogs",
                    saveLogs
            );

            dialog.dispose();
        });

        footer.add(testSound);
        footer.add(cancel);
        footer.add(save);

        dialog.setLayout(new BorderLayout());

        dialog.add(
                new JScrollPane(form),
                BorderLayout.CENTER
        );

        dialog.add(
                footer,
                BorderLayout.SOUTH
        );

        dialog.setVisible(true);
    }

    private void openLogFolder() {
        try {
            Files.createDirectories(HISTORY);

            if (
                    !Desktop.isDesktopSupported() ||
                            !Desktop.getDesktop().isSupported(
                                    Desktop.Action.OPEN
                            )
            ) {
                throw new IOException(
                        "Открытие папок не поддерживается в этой системе"
                );
            }

            Desktop.getDesktop().open(
                    HISTORY.toFile()
            );

        } catch (Exception ex) {
            JOptionPane.showMessageDialog(
                    frame,
                    "Не удалось открыть папку:\n" +
                            HISTORY + "\n" +
                            ex.getMessage(),
                    "Папка логов",
                    JOptionPane.ERROR_MESSAGE
            );
        }
    }

    private void clearAllLogs(Component parent) {
        if (
                active ||
                        processing.get() > 0
        ) {
            JOptionPane.showMessageDialog(
                    parent,
                    "Сейчас идёт звонок или распознавание. " +
                            "Дождись окончания, потом очисти логи.",
                    "Удаление логов",
                    JOptionPane.INFORMATION_MESSAGE
            );

            return;
        }

        int confirm = JOptionPane.showConfirmDialog(
                parent,
                "Удалить все сохранённые файлы из папки?\n" +
                        HISTORY +
                        "\n\nДействие нельзя отменить.",
                "Очистить все логи",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
        );

        if (
                confirm != JOptionPane.YES_OPTION
        ) {
            return;
        }

        try {
            if (!Files.exists(HISTORY)) {
                JOptionPane.showMessageDialog(
                        parent,
                        "Папка истории уже пуста."
                );
                return;
            }

            int deleted = 0;
            int failed = 0;

            try (
                    DirectoryStream<Path> files =
                            Files.newDirectoryStream(HISTORY)
            ) {
                for (Path file : files) {
                    if (
                            !Files.isRegularFile(
                                    file,
                                    LinkOption.NOFOLLOW_LINKS
                            )
                    ) {
                        continue;
                    }

                    if (
                            active ||
                                    processing.get() > 0
                    ) {
                        break;
                    }

                    try {
                        Files.delete(file);
                        deleted++;

                    } catch (IOException ex) {
                        failed++;
                    }
                }
            }

            JOptionPane.showMessageDialog(
                    parent,
                    "Удалено файлов: " + deleted +
                            (
                                    failed > 0
                                            ? "\nНе удалось удалить: " +
                                            failed
                                            : ""
                            ),
                    "Очистка логов",
                    JOptionPane.INFORMATION_MESSAGE
            );

        } catch (IOException ex) {
            JOptionPane.showMessageDialog(
                    parent,
                    "Ошибка очистки: " +
                            ex.getMessage(),
                    "Очистка логов",
                    JOptionPane.ERROR_MESSAGE
            );
        }
    }

    private void playSignal() {
        Mixer.Info chosen = null;

        if (!speakerName.isBlank()) {
            for (
                    Mixer.Info info :
                    AudioSystem.getMixerInfo()
            ) {
                if (
                        info.getName()
                                .equals(speakerName)
                ) {
                    chosen = info;
                    break;
                }
            }

            if (chosen == null) {
                chromeInfo.setText(
                        "Колонки не найдены: " +
                                speakerName
                );

                return;
            }
        }

        playSignalOn(chosen, volume);
    }

    private void playSignalOn(
            Mixer.Info mixer,
            int level
    ) {
        daemon(() -> {
            AudioFormat f = new AudioFormat(
                    44100,
                    16,
                    1,
                    true,
                    false
            );

            try (
                    SourceDataLine line =
                            mixer == null
                                    ? AudioSystem.getSourceDataLine(f)
                                    : AudioSystem.getSourceDataLine(
                                    f,
                                    mixer
                            )
            ) {
                line.open(f);
                line.start();

                for (
                        int tone = 0;
                        tone < 2;
                        tone++
                ) {
                    int count =
                            tone == 0 ? 7000 : 9500;

                    byte[] data =
                            new byte[count * 2];

                    for (
                            int i = 0;
                            i < count;
                            i++
                    ) {
                        double fade = Math.min(
                                1,
                                Math.min(
                                        i / 400.0,
                                        (count - i) / 400.0
                                )
                        );

                        short sample = (short) (
                                Math.sin(
                                        2 * Math.PI *
                                                (
                                                        tone == 0
                                                                ? 720
                                                                : 980
                                                ) *
                                                i / 44100.0
                                ) *
                                        24000 *
                                        fade *
                                        level / 100.0
                        );

                        data[2 * i] =
                                (byte) sample;

                        data[2 * i + 1] =
                                (byte) (sample >>> 8);
                    }

                    line.write(
                            data,
                            0,
                            data.length
                    );
                }

                line.drain();

            } catch (Exception ex) {
                ui(() -> chromeInfo.setText(
                        "Ошибка звука: " +
                                ex.getMessage()
                ));
            }
        }, "audio-alert").start();
    }

    private void startWhisperAutomatically() {
        Path root = Path.of(
                System.getProperty("user.dir")
        ).toAbsolutePath();

        Path python = root.resolve(
                ".venv/Scripts/python.exe"
        );

        Path script = root.resolve(
                "asr_server.py"
        );

        if (!useWhisper) {
            ui(() -> asrInfo.setText(
                    "Whisper отключён в настройках"
            ));
            return;
        }

        if (
                !Files.isRegularFile(python) ||
                        !Files.isRegularFile(script)
        ) {
            ui(() -> asrInfo.setText(
                    "Whisper: нет .venv или asr_server.py"
            ));
            return;
        }

        if (whisperReady()) {
            ui(() -> asrInfo.setText(
                    "Whisper: уже работает"
            ));
            return;
        }

        try {
            ProcessBuilder builder =
                    new ProcessBuilder(
                            python.toString(),
                            script.toString()
                    );

            builder.directory(root.toFile());
            builder.redirectErrorStream(true);

            builder.environment().put(
                    "PYTHONUNBUFFERED",
                    "1"
            );

            builder.environment().put(
                    "PYTHONIOENCODING",
                    "utf-8"
            );

            Process process = builder.start();
            whisperProcess = process;

            daemon(() -> {
                try (
                        BufferedReader reader =
                                new BufferedReader(
                                        new InputStreamReader(
                                                process.getInputStream(),
                                                StandardCharsets.UTF_8
                                        )
                                )
                ) {
                    String line;

                    while (
                            (line = reader.readLine()) != null
                    ) {
                        System.out.println(
                                "[Whisper] " + line
                        );
                    }

                } catch (IOException ignored) {
                }
            }, "whisper-log").start();

            for (
                    int i = 0;
                    i < 180 && !closing;
                    i++
            ) {
                if (whisperReady()) {
                    ui(() -> asrInfo.setText(
                            "Whisper RU+EN: готов · RTX 3050"
                    ));
                    return;
                }

                if (!process.isAlive()) {
                    ui(() -> asrInfo.setText(
                            "Whisper завершился · проверь консоль IntelliJ"
                    ));
                    return;
                }

                Thread.sleep(1000);
            }

        } catch (Exception ex) {
            ui(() -> asrInfo.setText(
                    "Whisper: " +
                            ex.getMessage()
            ));
        }
    }

    private boolean whisperReady() {
        try {
            HttpRequest request =
                    HttpRequest.newBuilder(
                                    URI.create(
                                            "http://127.0.0.1:" +
                                                    WHISPER_PORT +
                                                    "/health"
                                    )
                            )
                            .timeout(
                                    Duration.ofSeconds(2)
                            )
                            .GET()
                            .build();

            HttpResponse<String> response =
                    http.send(
                            request,
                            HttpResponse.BodyHandlers
                                    .ofString(
                                            StandardCharsets.UTF_8
                                    )
                    );

            return response.statusCode() == 200 &&
                    "ready".equals(
                            field(
                                    response.body(),
                                    "status"
                            )
                    );

        } catch (Exception ex) {
            return false;
        }
    }

    private void stopWhisperAutomatically() {
        Process p = whisperProcess;
        whisperProcess = null;

        if (
                p != null &&
                        p.isAlive()
        ) {
            p.destroy();
        }
    }

    private void shutdown() {
        closing = true;
        generation.incrementAndGet();

        if (uiTimer != null) {
            uiTimer.stop();
        }

        if (phoneServer != null) {
            phoneServer.stop(0);
        }

        if (chromeServer != null) {
            chromeServer.stop(0);
        }

        if (sipSocket != null) {
            sipSocket.close();
        }

        if (inputLine != null) {
            inputLine.close();
        }

        dialWorker.shutdownNow();
        speechWorker.shutdownNow();

        stopWhisperAutomatically();

        frame.dispose();
    }
}
