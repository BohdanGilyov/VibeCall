
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
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class Main {
    private static final String PC = "192.168.50.1";
    private static final String SNOM = "192.168.50.2";
    private static final int PHONE_PORT = 47622;
    private static final int MICROSIP_PORT = 47621;
    private static final int CHROME_PORT = 47624;
    private static final float VOSK_HZ = 16000;
    private static final long FINISH_DELAY_NS = 500_000_000L;
    private static final Path HISTORY = Path.of("transcripts");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss_SSS");
    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final Color BG = new Color(10, 17, 29);
    private static final Color CARD = new Color(22, 34, 52);
    private static final Color SECOND = new Color(33, 49, 71);
    private static final Color FG = new Color(242, 248, 255);
    private static final Color MUTE = new Color(153, 172, 193);
    private static final Color CYAN = new Color(80, 222, 192);
    private static final Color BLUE = new Color(116, 181, 255);

    private final JFrame frame = new JFrame("Snom AI Bridge 4.1");
    private final JLabel phoneStatus =
            text("Ожидание снятия трубки", 19, true, FG);
    private final JLabel phoneDetail =
            text("Snom: подключение по 192.168.50.1:47622", 12, false, MUTE);
    private final JLabel speechStatus =
            text("Загрузка Vosk...", 13, false, MUTE);
    private final JLabel chromeStatus =
            text("Ожидаю вкладку ChatGPT...", 13, false, MUTE);
    private final JLabel partial =
            text("Здесь появятся распознаваемые слова", 13, false, BLUE);
    private final JLabel clock = text("00:00", 34, true, FG);
    private final JTextArea transcript = new JTextArea();
    private final JProgressBar meter = new JProgressBar(0, 100);
    private final JComboBox<Tab> targetBox = new JComboBox<>();
    private final JCheckBox autoSend =
            new JCheckBox("Отправлять в ChatGPT автоматически", true);
    private final JTextField httpUser = new JTextField(7);
    private final JPasswordField httpPass = new JPasswordField(9);

    private final BlockingQueue<String> phoneEvents =
            new LinkedBlockingQueue<>();
    private final ConcurrentMap<Long, Tab> tabs =
            new ConcurrentHashMap<>();
    private final AtomicLong jobSequence = new AtomicLong(1);
    private final ExecutorService dialExecutor =
            Executors.newSingleThreadExecutor(
                    r -> daemon(r, "snom-dial")
            );

    private volatile BrowserJob browserJob;
    private volatile long selectedTab = -1;
    private volatile long pendingJob = -1;
    private volatile long pendingTab = -1;
    private volatile boolean closing;
    private volatile boolean recording;
    private volatile long startNanos;
    private volatile long frozenNanos;
    private volatile String lastPrompt = "";
    private volatile TargetDataLine inputLine;
    private volatile boolean autoSubmit = true;

    private boolean active;
    private boolean finishing;
    private boolean testSession;
    private volatile boolean callEstablished;
    private long finishAt;
    private long lastHangupAt;
    private Path sessionFile;
    private final StringBuilder spoken = new StringBuilder();
    private boolean rebuildingTabs;
    private boolean warnedMissingCall;

    private HttpServer browserServer;
    private HttpServer snomServer;
    private DatagramSocket sipSocket;
    private javax.swing.Timer swingTimer;

    private record Tab(
            long id,
            long window,
            String title,
            long seen
    ) {
        @Override
        public String toString() {
            return title + "   ·   окно " + window;
        }
    }

    private record BrowserJob(long id, long tabId, String text) {}

    private record AudioIn(
            TargetDataLine line,
            AudioFormat format,
            String name
    ) {}

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new Main().start());
    }

    private void start() {
        frame.setDefaultCloseOperation(
                WindowConstants.DO_NOTHING_ON_CLOSE
        );
        frame.setSize(1040, 770);
        frame.setMinimumSize(new Dimension(850, 630));
        frame.setLocationRelativeTo(null);
        frame.getContentPane().setBackground(BG);
        frame.setLayout(new BorderLayout(0, 14));

        Rounded hero = new Rounded(
                22,
                new Color(23, 64, 84),
                new Color(23, 46, 93)
        );
        hero.setBorder(new EmptyBorder(22, 26, 20, 26));
        hero.setLayout(new BorderLayout());

        JPanel heroLeft = transparent();
        heroLeft.setLayout(
                new BoxLayout(heroLeft, BoxLayout.Y_AXIS)
        );
        heroLeft.add(text(
                "S N O M  /  A I   B R I D G E",
                12, true, CYAN
        ));
        heroLeft.add(Box.createVerticalStrut(12));
        heroLeft.add(text(
                "Твой голос. Твой ChatGPT.",
                27, true, FG
        ));
        heroLeft.add(Box.createVerticalStrut(9));
        heroLeft.add(text(
                "Сними трубку → диктуй → положи → получи сигнал",
                13, false, FG
        ));
        hero.add(heroLeft, BorderLayout.CENTER);

        JPanel heroRight = transparent();
        heroRight.setLayout(
                new BoxLayout(heroRight, BoxLayout.Y_AXIS)
        );
        clock.setAlignmentX(Component.RIGHT_ALIGNMENT);
        phoneStatus.setAlignmentX(Component.RIGHT_ALIGNMENT);
        heroRight.add(clock);
        heroRight.add(Box.createVerticalStrut(8));
        heroRight.add(phoneStatus);
        hero.add(heroRight, BorderLayout.EAST);

        JPanel north = transparent();
        north.setBorder(new EmptyBorder(18, 22, 0, 22));
        north.setLayout(new BorderLayout());
        north.add(hero);
        frame.add(north, BorderLayout.NORTH);

        JPanel middle = transparent();
        middle.setBorder(new EmptyBorder(0, 22, 0, 22));
        middle.setLayout(new BorderLayout(0, 12));

        Rounded statusCard = new Rounded(18, CARD, CARD);
        statusCard.setBorder(new EmptyBorder(14, 18, 14, 18));
        statusCard.setLayout(new GridLayout(3, 1, 0, 7));
        statusCard.add(phoneDetail);
        statusCard.add(speechStatus);
        statusCard.add(chromeStatus);
        middle.add(statusCard, BorderLayout.NORTH);

        Rounded textCard = new Rounded(18, CARD, CARD);
        textCard.setBorder(new EmptyBorder(14, 18, 14, 18));
        textCard.setLayout(new BorderLayout(0, 10));
        textCard.add(
                text("РАСШИФРОВКА РАЗГОВОРА", 13, true, CYAN),
                BorderLayout.NORTH
        );

        transcript.setEditable(false);
        transcript.setLineWrap(true);
        transcript.setWrapStyleWord(true);
        transcript.setFont(
                new Font("Segoe UI", Font.PLAIN, 17)
        );
        transcript.setForeground(FG);
        transcript.setBackground(CARD);
        transcript.setCaretColor(FG);
        transcript.setText(
                "Готово к работе. Сними трубку Snom 320 и говори.\n"
        );

        JScrollPane scroll = new JScrollPane(transcript);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(CARD);
        textCard.add(scroll, BorderLayout.CENTER);

        meter.setForeground(CYAN);
        meter.setBackground(SECOND);
        meter.setBorderPainted(false);
        meter.setPreferredSize(new Dimension(200, 9));

        JPanel speechFooter = transparent();
        speechFooter.setLayout(new BorderLayout(0, 7));
        speechFooter.add(partial, BorderLayout.CENTER);
        speechFooter.add(meter, BorderLayout.SOUTH);
        textCard.add(speechFooter, BorderLayout.SOUTH);

        middle.add(textCard, BorderLayout.CENTER);
        frame.add(middle, BorderLayout.CENTER);

        Rounded controls = new Rounded(18, CARD, CARD);
        controls.setBorder(new EmptyBorder(15, 18, 17, 18));
        controls.setLayout(new BorderLayout(0, 12));

        JPanel selectRow = transparent();
        selectRow.setLayout(new BorderLayout(12, 0));
        selectRow.add(
                text("КУДА ОТПРАВЛЯТЬ", 12, true, MUTE),
                BorderLayout.WEST
        );

        targetBox.setFont(
                new Font("Segoe UI", Font.PLAIN, 14)
        );
        targetBox.setBackground(SECOND);
        targetBox.setForeground(FG);

        targetBox.addActionListener(e -> {
            if (!rebuildingTabs) {
                Tab t = (Tab) targetBox.getSelectedItem();
                selectedTab = t == null ? -1 : t.id();
            }
        });

        selectRow.add(targetBox, BorderLayout.CENTER);
        controls.add(selectRow, BorderLayout.NORTH);

        JPanel buttons = transparent();
        buttons.setLayout(
                new FlowLayout(FlowLayout.LEFT, 9, 0)
        );

        buttons.add(button(
                "Начать тест",
                true,
                () -> phoneEvents.offer("test")
        ));

        buttons.add(button(
                "Завершить",
                false,
                () -> phoneEvents.offer("hangup")
        ));

        buttons.add(button(
                "Отправить текст",
                false,
                () -> queueBrowserJob(lastPrompt)
        ));

        buttons.add(button(
                "Тест ChatGPT",
                false,
                () -> queueBrowserJob(
                        "Проверка связи. Ответь одним коротким предложением."
                )
        ));

        buttons.add(button(
                "Проверить звук",
                false,
                this::playSignal
        ));

        controls.add(buttons, BorderLayout.CENTER);

        JPanel preferences = transparent();
        preferences.setLayout(
                new FlowLayout(FlowLayout.LEFT, 12, 0)
        );

        autoSend.setOpaque(false);
        autoSend.setForeground(FG);
        autoSend.addActionListener(
                e -> autoSubmit = autoSend.isSelected()
        );
        preferences.add(autoSend);

        preferences.add(text(
                "HTTP пользователь Snom:",
                12, false, MUTE
        ));
        preferences.add(httpUser);

        preferences.add(text(
                "пароль:", 12, false, MUTE
        ));
        preferences.add(httpPass);
        controls.add(preferences, BorderLayout.SOUTH);

        JPanel south = transparent();
        south.setBorder(new EmptyBorder(0, 22, 20, 22));
        south.setLayout(new BorderLayout());
        south.add(controls);
        frame.add(south, BorderLayout.SOUTH);

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

        swingTimer = new javax.swing.Timer(400, e -> {
            long seconds = (
                    recording
                            ? System.nanoTime() - startNanos
                            : frozenNanos
            ) / 1_000_000_000L;

            clock.setText(String.format(
                    Locale.ROOT,
                    "%02d:%02d",
                    seconds / 60,
                    seconds % 60
            ));

            refreshTabs();

            if (recording &&
                    !callEstablished &&
                    !warnedMissingCall &&
                    System.nanoTime() - startNanos > 5_000_000_000L) {

                warnedMissingCall = true;

                phoneDetail.setText(
                        "Нет подтверждения от 101: проверь регистрацию MicroSIP"
                );
            }
        });

        swingTimer.start();
        frame.setVisible(true);

        startBrowserServer();
        startPhoneServer();
        startMicroSipServer();

        Thread audio = daemon(
                this::capture,
                "vosk-audio"
        );
        audio.start();
    }

    private static Thread daemon(Runnable task, String name) {
        Thread t = new Thread(task, name);
        t.setDaemon(true);
        return t;
    }

    private static JPanel transparent() {
        JPanel p = new JPanel();
        p.setOpaque(false);
        return p;
    }

    private static JLabel text(
            String value,
            int size,
            boolean bold,
            Color color
    ) {
        JLabel l = new JLabel(value);
        l.setFont(new Font(
                "Segoe UI",
                bold ? Font.BOLD : Font.PLAIN,
                size
        ));
        l.setForeground(color);
        return l;
    }

    private JButton button(
            String title,
            boolean primary,
            Runnable action
    ) {
        JButton b = new JButton(title);
        b.setBackground(primary ? CYAN : SECOND);
        b.setForeground(primary ? BG : FG);
        b.setFont(
                new Font("Segoe UI", Font.BOLD, 12)
        );
        b.setFocusPainted(false);
        b.setBorder(
                new EmptyBorder(11, 14, 11, 14)
        );
        b.setCursor(
                Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        );
        b.addActionListener(e -> action.run());
        return b;
    }

    private static class Rounded extends JPanel {
        private final int r;
        private final Color a, b;

        Rounded(int r, Color a, Color b) {
            this.r = r;
            this.a = a;
            this.b = b;
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON
            );
            g2.setPaint(new GradientPaint(
                    0, 0, a,
                    getWidth(), getHeight(), b
            ));
            g2.fillRoundRect(
                    0, 0,
                    getWidth(), getHeight(),
                    r, r
            );
            g2.dispose();
            super.paintComponent(g);
        }
    }

    private void refreshTabs() {
        long now = System.currentTimeMillis();

        List<Tab> available = tabs.values().stream()
                .filter(t -> now - t.seen() < 10000)
                .sorted(
                        Comparator.comparing(Tab::title)
                                .thenComparingLong(Tab::id)
                )
                .toList();

        boolean changed =
                available.size() != targetBox.getItemCount();

        if (!changed) {
            for (int i = 0; i < available.size(); i++) {
                Tab a = available.get(i);
                Tab b = targetBox.getItemAt(i);

                if (a.id() != b.id() ||
                        a.window() != b.window() ||
                        !a.title().equals(b.title())) {
                    changed = true;
                    break;
                }
            }
        }

        if (!changed) return;

        long previous = selectedTab;
        rebuildingTabs = true;
        targetBox.removeAllItems();

        int select = -1;

        for (int i = 0; i < available.size(); i++) {
            Tab t = available.get(i);
            targetBox.addItem(t);
            if (t.id() == previous) select = i;
        }

        if (select < 0 && !available.isEmpty()) {
            select = 0;
        }

        targetBox.setSelectedIndex(select);

        selectedTab = select < 0
                ? -1
                : available.get(select).id();

        rebuildingTabs = false;

        if (selectedTab >= 0 && pendingJob == -1) {
            chromeStatus.setText(
                    "Chrome подключён · выбрана вкладка ChatGPT"
            );
        }

        if (available.isEmpty()) {
            chromeStatus.setText(
                    "Chrome не найден: открой ChatGPT и обнови страницу F5"
            );
        }
    }

    private void startBrowserServer() {
        try {
            browserServer = HttpServer.create(
                    new InetSocketAddress(
                            "127.0.0.1",
                            CHROME_PORT
                    ),
                    0
            );

            browserServer.createContext("/poll", x -> {
                try {
                    if (!browserAuthorized(x)) return;

                    JsonNode data =
                            JSON.readTree(x.getRequestBody());

                    long tab = data.path("tabId").asLong(-1);
                    long window = data.path("windowId").asLong(-1);

                    String url = data.path("url").asText("");
                    String title = data.path("title")
                            .asText("ChatGPT");

                    if (tab < 0 ||
                            !(url.startsWith("https://chatgpt.com/") ||
                                    url.startsWith("https://chat.openai.com/"))) {

                        writeJson(
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
                                    title,
                                    System.currentTimeMillis()
                            )
                    );

                    ObjectNode response =
                            JSON.createObjectNode();

                    BrowserJob job = browserJob;

                    if (job != null && job.tabId() == tab) {
                        synchronized (this) {
                            if (browserJob == job) {
                                browserJob = null;

                                ObjectNode j =
                                        response.putObject("job");

                                j.put("id", job.id());
                                j.put("text", job.text());
                            }
                        }
                    }

                    writeJson(x, 200, response);

                } catch (Exception ignored) {
                    safeError(x);
                }
            });

            browserServer.createContext("/event", x -> {
                try {
                    if (!browserAuthorized(x)) return;

                    JsonNode data =
                            JSON.readTree(x.getRequestBody());

                    long jobId =
                            data.path("jobId").asLong(-1);

                    long tab =
                            data.path("tabId").asLong(-1);

                    String kind =
                            data.path("kind").asText("");

                    String error =
                            data.path("message").asText("");

                    if (jobId == pendingJob && tab == pendingTab) {
                        ui(() -> {
                            if (kind.equals("sent")) {
                                chromeStatus.setText(
                                        "Запрос отправлен · ожидаю ответ..."
                                );
                            }

                            if (kind.equals("error")) {
                                chromeStatus.setText(
                                        "Ошибка Chrome: " + error
                                );
                            }

                            if (kind.equals("done")) {
                                chromeStatus.setText(
                                        "Ответ готов! Звуковой сигнал подан."
                                );
                                playSignal();
                            }
                        });

                        if (kind.equals("done") ||
                                kind.equals("error")) {

                            pendingJob = -1;
                            pendingTab = -1;
                        }
                    }

                    writeJson(
                            x,
                            200,
                            JSON.createObjectNode().put("ok", true)
                    );

                } catch (Exception ignored) {
                    safeError(x);
                }
            });

            browserServer.setExecutor(
                    Executors.newCachedThreadPool(
                            r -> daemon(r, "chrome-bridge")
                    )
            );

            browserServer.start();

        } catch (IOException ex) {
            chromeStatus.setText(
                    "Не удалось открыть порт " +
                            CHROME_PORT + ": " +
                            ex.getMessage()
            );
        }
    }

    private boolean browserAuthorized(
            HttpExchange x
    ) throws IOException {

        if (!"POST".equals(x.getRequestMethod()) ||
                !"chrome-ext-v1".equals(
                        x.getRequestHeaders()
                                .getFirst("X-Snom-Bridge")
                )) {

            writeJson(
                    x,
                    403,
                    JSON.createObjectNode()
            );
            return false;
        }

        return true;
    }

    private void writeJson(
            HttpExchange x,
            int code,
            JsonNode obj
    ) throws IOException {

        byte[] bytes = JSON.writeValueAsBytes(obj);

        x.getResponseHeaders().set(
                "Content-Type",
                "application/json; charset=UTF-8"
        );

        x.sendResponseHeaders(code, bytes.length);

        try (var out = x.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void safeError(HttpExchange x) {
        try {
            writeJson(
                    x,
                    500,
                    JSON.createObjectNode()
            );
        } catch (IOException ignored) {
        }
    }

    private void startPhoneServer() {
        try {
            snomServer = HttpServer.create(
                    new InetSocketAddress(PC, PHONE_PORT),
                    0
            );

            snomServer.createContext("/", x -> {
                String remote = x.getRemoteAddress()
                        .getAddress()
                        .getHostAddress();

                String path = x.getRequestURI().getPath();

                String event = switch (path) {
                    case "/offhook" -> "offhook";
                    case "/outgoing", "/connected" -> "connected";
                    case "/hangup", "/disconnected" -> "hangup";
                    default -> "";
                };

                boolean allowed =
                        (remote.equals(SNOM) || remote.equals(PC)) &&
                                x.getRequestMethod().equals("GET");

                int code = !allowed
                        ? 403
                        : event.isEmpty() ? 404 : 200;

                byte[] body = (
                        code == 200 ? "OK" : "Not found"
                ).getBytes(StandardCharsets.UTF_8);

                x.getResponseHeaders().set(
                        "Content-Type",
                        "text/plain; charset=utf-8"
                );

                x.sendResponseHeaders(code, body.length);

                try (var out = x.getResponseBody()) {
                    out.write(body);
                }

                if (code == 200) {
                    phoneEvents.offer(event);
                }
            });

            snomServer.setExecutor(
                    Executors.newCachedThreadPool(
                            r -> daemon(r, "snom-hooks")
                    )
            );

            snomServer.start();

            phoneDetail.setText(
                    "Snom HTTP готов · " + PC + ":" + PHONE_PORT
            );

        } catch (IOException ex) {
            phoneDetail.setText(
                    "Snom HTTP не запустился: " +
                            ex.getMessage()
            );
        }
    }

    private void startMicroSipServer() {
        try {
            sipSocket = new DatagramSocket(
                    new InetSocketAddress(
                            "127.0.0.1",
                            MICROSIP_PORT
                    )
            );

            daemon(() -> {
                byte[] b = new byte[1024];

                while (!closing) {
                    try {
                        DatagramPacket p =
                                new DatagramPacket(b, b.length);

                        sipSocket.receive(p);

                        String msg = new String(
                                p.getData(),
                                p.getOffset(),
                                p.getLength(),
                                StandardCharsets.UTF_8
                        ).split("\\|", 2)[0]
                                .trim()
                                .toLowerCase(Locale.ROOT);

                        if (msg.equals("connected")) {
                            phoneEvents.offer("connected");
                        }

                        if (msg.equals("ended")) {
                            phoneEvents.offer("hangup");
                        }

                    } catch (Exception ignored) {
                    }
                }

            }, "microsip-hooks").start();

        } catch (SocketException ex) {
            phoneDetail.setText(
                    "MicroSIP: UDP 47621 занят, проверь другие запущенные копии"
            );
        }
    }

    private void dial101() {
        String user = httpUser.getText().trim();
        String password = new String(httpPass.getPassword());

        dialExecutor.submit(() -> {
            String query =
                    "number=101&outgoing_uri=100%40192.168.50.1";

            String[] pages = {
                    "command.htm",
                    "index.htm"
            };

            for (String page : pages) {
                try {
                    URL url = URI.create(
                            "http://" + SNOM + "/" +
                                    page + "?" + query
                    ).toURL();

                    HttpURLConnection conn =
                            (HttpURLConnection) url.openConnection();

                    conn.setConnectTimeout(3500);
                    conn.setReadTimeout(3500);
                    conn.setRequestMethod("GET");

                    if (!user.isBlank()) {
                        conn.setAuthenticator(new Authenticator() {
                            @Override
                            protected PasswordAuthentication
                            getPasswordAuthentication() {

                                return new PasswordAuthentication(
                                        user,
                                        password.toCharArray()
                                );
                            }
                        });
                    }

                    int code = conn.getResponseCode();
                    conn.disconnect();

                    if (code >= 200 && code < 400) {
                        ui(() -> phoneDetail.setText(
                                "Команда набора 101 отправлена · ждём SIP-соединение"
                        ));
                        return;
                    }

                    if (code == 401 || code == 403) {
                        ui(() -> phoneDetail.setText(
                                "Snom требует HTTP-авторизацию. " +
                                        "Укажи логин/пароль HTTP внизу."
                        ));
                        return;
                    }

                    if (code != 404) {
                        ui(() -> phoneDetail.setText(
                                "Snom отказал в наборе: HTTP " + code
                        ));
                        return;
                    }

                } catch (Exception ex) {
                    if (page.equals(pages[pages.length - 1])) {
                        ui(() -> phoneDetail.setText(
                                "Не удалось набрать 101: " +
                                        ex.getMessage()
                        ));
                    }
                }
            }
        });
    }

    private void queueBrowserJob(String text) {
        if (text == null || text.isBlank()) {
            chromeStatus.setText(
                    "Нет готового текста для отправки"
            );
            return;
        }

        Tab target = tabs.get(selectedTab);

        if (target == null ||
                System.currentTimeMillis() -
                        target.seen() > 10000) {

            chromeStatus.setText(
                    "Выбери вкладку ChatGPT из списка выше"
            );
            return;
        }

        BrowserJob job = new BrowserJob(
                jobSequence.getAndIncrement(),
                target.id(),
                text
        );

        browserJob = job;
        pendingJob = job.id();
        pendingTab = job.tabId();

        chromeStatus.setText(
                "Ожидаю отправку запроса во вкладку «" +
                        target.title() + "»..."
        );
    }

    private void capture() {
        if (!Files.isDirectory(Path.of("model/am"))) {
            ui(() -> speechStatus.setText(
                    "Модель Vosk не найдена: нужна папка model рядом с pom.xml"
            ));
            return;
        }

        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS);

            try (
                    Model model = new Model("model");
                    Recognizer rec = new Recognizer(
                            model, VOSK_HZ
                    )
            ) {
                AudioIn ai = findCable();

                TargetDataLine line = ai.line();
                inputLine = line;

                Resampler resampler = new Resampler(
                        ai.format().getSampleRate(),
                        ai.format().getChannels()
                );

                byte[] input = new byte[8192];
                byte[] pcm = new byte[16384];

                line.start();

                long nextPreview = 0;
                long nextMeter = 0;

                ui(() -> speechStatus.setText(
                        "Vosk готов · " +
                                ai.name() + " · " +
                                (int) ai.format().getSampleRate() +
                                " Гц"
                ));

                while (!closing) {
                    String event;

                    while ((event = phoneEvents.poll()) != null) {

                        if (event.equals("offhook") ||
                                event.equals("test") ||
                                event.equals("connected")) {

                            if (!active) {
                                if (System.nanoTime() -
                                        lastHangupAt <
                                        700_000_000L &&
                                        !event.equals("offhook")) {
                                    continue;
                                }

                                active = true;
                                finishing = false;
                                testSession = event.equals("test");
                                callEstablished =
                                        event.equals("connected");

                                warnedMissingCall = false;
                                recording = true;

                                startNanos = System.nanoTime();
                                frozenNanos = 0;

                                spoken.setLength(0);
                                rec.reset();
                                resampler.reset();
                                line.flush();

                                sessionFile = HISTORY.resolve(
                                        FILE_STAMP.format(
                                                LocalDateTime.now()
                                        ) + "_call.txt"
                                );

                                ui(() -> {
                                    transcript.setText("");
                                    phoneStatus.setText(
                                            "Трубка поднята · слушаю голос"
                                    );
                                    partial.setText("Слушаю...");
                                });

                                if (event.equals("offhook")) {
                                    ui(this::dial101);
                                }

                            } else if (event.equals("connected")) {
                                callEstablished = true;

                                ui(() -> phoneDetail.setText(
                                        "Номер 101 ответил · можно говорить"
                                ));
                            }

                        } else if (
                                event.equals("hangup") &&
                                        active &&
                                        !finishing
                        ) {
                            finishing = true;
                            recording = false;

                            frozenNanos =
                                    System.nanoTime() - startNanos;

                            finishAt =
                                    System.nanoTime() +
                                            FINISH_DELAY_NS;

                            ui(() -> phoneStatus.setText(
                                    "Трубка положена · сохраняю последнюю фразу"
                            ));
                        }
                    }

                    if (active &&
                            finishing &&
                            System.nanoTime() >= finishAt) {

                        appendFinal(rec.getFinalResult());

                        active = false;
                        finishing = false;
                        lastHangupAt = System.nanoTime();

                        String result =
                                spoken.toString().trim();

                        ui(() -> {
                            lastPrompt = result;
                            meter.setValue(0);
                            partial.setText(" ");

                            phoneStatus.setText(
                                    "Готово · ожидаю следующего звонка"
                            );

                            if (!result.isBlank()) {
                                try {
                                    Toolkit.getDefaultToolkit()
                                            .getSystemClipboard()
                                            .setContents(
                                                    new StringSelection(result),
                                                    null
                                            );
                                } catch (IllegalStateException ignored) {
                                }

                                if (autoSubmit && !testSession) {
                                    queueBrowserJob(result);
                                }

                            } else {
                                chromeStatus.setText(
                                        "Нет распознанной речи. " +
                                                "Проверь регистрацию 101 в miniSIPServer."
                                );
                            }
                        });

                        rec.reset();
                        resampler.reset();
                    }

                    int n = Math.min(
                            line.available(),
                            input.length
                    );

                    n -= n % ai.format().getFrameSize();

                    if (n < ai.format().getFrameSize()) {
                        Thread.sleep(15);
                        continue;
                    }

                    int got = line.read(input, 0, n);

                    if (got <= 0 || !active) {
                        continue;
                    }

                    long now = System.nanoTime();

                    if (now >= nextMeter) {
                        int value = audioLevel(
                                input,
                                got,
                                ai.format().getChannels()
                        );

                        ui(() -> meter.setValue(value));

                        nextMeter =
                                now + 100_000_000L;
                    }

                    int converted = resampler.convert(
                            input,
                            got,
                            pcm
                    );

                    if (converted <= 0) {
                        continue;
                    }

                    if (rec.acceptWaveForm(pcm, converted)) {
                        appendFinal(rec.getResult());

                        ui(() -> partial.setText(
                                "Слушаю..."
                        ));

                    } else if (now >= nextPreview) {
                        String phrase = jsonText(
                                rec.getPartialResult(),
                                "partial"
                        );

                        ui(() -> partial.setText(
                                phrase.isBlank()
                                        ? "Слушаю..."
                                        : "Слышу: " + phrase
                        ));

                        nextPreview =
                                now + 250_000_000L;
                    }
                }

                line.close();
            }

        } catch (Exception ex) {
            if (!closing) {
                ui(() -> speechStatus.setText(
                        "Ошибка захвата звука: " +
                                ex.getMessage()
                ));
            }
        }
    }

    private void appendFinal(String result) {
        String phrase = jsonText(result, "text");

        if (phrase.isBlank()) return;

        if (!spoken.isEmpty()) {
            spoken.append(' ');
        }

        spoken.append(phrase);

        String line =
                CLOCK.format(LocalDateTime.now()) +
                        "   " + phrase;

        try {
            Files.createDirectories(HISTORY);

            Files.writeString(
                    sessionFile,
                    line + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );

        } catch (IOException ex) {
            ui(() -> speechStatus.setText(
                    "Ошибка сохранения: " +
                            ex.getMessage()
            ));
        }

        ui(() -> {
            transcript.append(line + "\n\n");

            transcript.setCaretPosition(
                    transcript.getDocument().getLength()
            );
        });
    }

    private static String jsonText(
            String data,
            String key
    ) {
        try {
            return JSON.readTree(data)
                    .path(key)
                    .asText("")
                    .trim();

        } catch (Exception ex) {
            return "";
        }
    }

    private static AudioIn findCable()
            throws LineUnavailableException {

        AudioFormat[] formats = {
                new AudioFormat(48000, 16, 2, true, false),
                new AudioFormat(48000, 16, 1, true, false),
                new AudioFormat(44100, 16, 2, true, false),
                new AudioFormat(16000, 16, 1, true, false)
        };

        for (Mixer.Info mixer :
                AudioSystem.getMixerInfo()) {

            String name = mixer.getName()
                    .toLowerCase(Locale.ROOT);

            if (!name.contains("cable output") &&
                    !name.contains("vb-audio virtual cable")) {
                continue;
            }

            for (AudioFormat format : formats) {
                try {
                    TargetDataLine line =
                            AudioSystem.getTargetDataLine(
                                    format,
                                    mixer
                            );

                    line.open(format, 16384);

                    return new AudioIn(
                            line,
                            format,
                            mixer.getName()
                    );

                } catch (Exception ignored) {
                }
            }
        }

        throw new LineUnavailableException(
                "Не найден CABLE Output в устройствах записи Windows"
        );
    }

    private static int audioLevel(
            byte[] data,
            int count,
            int channels
    ) {
        double energy = 0;
        int frames = 0;

        for (int i = 0;
             i + channels * 2 <= count;
             i += channels * 2) {

            int sample = (short) (
                    (data[i] & 255) |
                            (data[i + 1] << 8)
            );

            energy += (double) sample * sample;
            frames++;
        }

        return Math.min(
                100,
                (int) (
                        Math.sqrt(
                                energy / Math.max(frames, 1)
                        ) / 110
                )
        );
    }

    private static class Resampler {
        private final int channels;
        private final double ratio;
        private double remaining, sum;

        Resampler(float rate, int channels) {
            this.channels = channels;
            this.ratio = rate / VOSK_HZ;
            reset();
        }

        void reset() {
            remaining = ratio;
            sum = 0;
        }

        int convert(
                byte[] input,
                int length,
                byte[] out
        ) {
            int position = 0;
            int frame = channels * 2;

            for (int i = 0;
                 i + frame <= length;
                 i += frame) {

                double value = 0;

                for (int channel = 0;
                     channel < channels;
                     channel++) {

                    int j = i + channel * 2;

                    value += (short) (
                            (input[j] & 255) |
                                    (input[j + 1] << 8)
                    );
                }

                value /= channels;
                double available = 1;

                while (available > 1e-9) {
                    double part = Math.min(
                            available,
                            remaining
                    );

                    sum += value * part;
                    remaining -= part;
                    available -= part;

                    if (remaining < 1e-9) {
                        int sample = Math.max(
                                -32768,
                                Math.min(
                                        32767,
                                        (int) Math.round(
                                                sum / ratio
                                        )
                                )
                        );

                        out[position++] =
                                (byte) sample;

                        out[position++] =
                                (byte) (sample >>> 8);

                        remaining = ratio;
                        sum = 0;
                    }
                }
            }

            return position;
        }
    }

    private void playSignal() {
        daemon(() -> {
            AudioFormat format =
                    new AudioFormat(
                            44100, 16, 1, true, false
                    );

            try (
                    SourceDataLine out =
                            AudioSystem.getSourceDataLine(format)
            ) {
                out.open(format);
                out.start();

                for (int tone = 0; tone < 2; tone++) {
                    int freq = tone == 0 ? 720 : 960;

                    byte[] samples =
                            new byte[17640];

                    for (int i = 0;
                         i < samples.length / 2;
                         i++) {

                        short s = (short) (
                                Math.sin(
                                        2 * Math.PI *
                                                freq * i / 44100
                                ) * 7500
                        );

                        samples[2 * i] =
                                (byte) s;

                        samples[2 * i + 1] =
                                (byte) (s >>> 8);
                    }

                    out.write(
                            samples,
                            0,
                            samples.length
                    );
                }

                out.drain();

            } catch (Exception ignored) {
                Toolkit.getDefaultToolkit().beep();
            }

        }, "ready-signal").start();
    }

    private void shutdown() {
        closing = true;

        if (swingTimer != null) {
            swingTimer.stop();
        }

        if (browserServer != null) {
            browserServer.stop(0);
        }

        if (snomServer != null) {
            snomServer.stop(0);
        }

        if (sipSocket != null) {
            sipSocket.close();
        }

        if (inputLine != null) {
            inputLine.close();
        }

        dialExecutor.shutdownNow();
        frame.dispose();
    }

    private static void ui(Runnable task) {
        SwingUtilities.invokeLater(task);
    }
}
