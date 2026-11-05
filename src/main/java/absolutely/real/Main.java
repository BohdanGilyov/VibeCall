
package absolutely.real;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

import javax.sound.sampled.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
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

public class Main {

    private static final int EVENT_PORT = 47621;
    private static final int SNOM_HOOK_PORT = 47622;
    private static final String PC_IP = "192.168.50.1";
    private static final String SNOM_IP = "192.168.50.2";

    private static final float VOSK_RATE = 16000;
    private static final long HANGUP_GRACE_NS = 600_000_000L;
    private static final String MODEL_NAME = "gpt-4.1-mini";

    private static final Path HISTORY = Path.of("transcripts");

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss_SSS");

    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Color BG = new Color(15, 23, 42);
    private static final Color CARD = new Color(30, 41, 59);
    private static final Color FG = new Color(241, 245, 249);
    private static final Color SECONDARY = new Color(148, 163, 184);
    private static final Color GREEN = new Color(74, 222, 128);

    private final JFrame frame =
            new JFrame("Snom Transcriber 3.2 · AI Call");

    private final JLabel status =
            new JLabel("Ожидание звонка на 101");

    private final JLabel duration = new JLabel("00:00");

    private final JLabel audioStatus =
            new JLabel("Подключение к VB-CABLE...");

    private final JLabel apiStatus =
            new JLabel("OpenAI API: проверка ключа...");

    private final JLabel preview = new JLabel("Слушаю...");

    private final JLabel eventStatus =
            new JLabel("MicroSIP: ожидаю событие на 127.0.0.1:47621");

    private final JLabel hookStatus =
            new JLabel("Snom: ожидаю сигнал при укладывании трубки");

    private final JProgressBar level =
            new JProgressBar(0, 100);

    private final JTextArea words = new JTextArea();
    private final JTextArea answer = new JTextArea();

    private final JCheckBox automatic =
            new JCheckBox("Отправлять текст в ИИ после звонка", true);

    private final JCheckBox testMode =
            new JCheckBox("Тест без телефонного звонка");

    private final BlockingQueue<PhoneEvent> events =
            new LinkedBlockingQueue<>();

    private final ExecutorService apiWorker =
            Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "openai-api");
                thread.setDaemon(true);
                return thread;
            });

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(12))
            .build();

    private final String apiKey =
            System.getenv("OPENAI_API_KEY");

    private volatile boolean closing;
    private volatile boolean creditsExhausted;
    private volatile boolean autoSend = true;
    private volatile boolean connected;

    private volatile long startedAt;
    private volatile long stoppedDuration;

    private volatile String lastPrompt = "";
    private volatile TargetDataLine audioLine;

    private DatagramSocket socket;
    private ServerSocket snomHookServer;
    private javax.swing.Timer timer;

    private boolean activeSession;
    private boolean testSession;
    private long finishAt;
    private Path callFile;

    private final StringBuilder recognized =
            new StringBuilder();

    private record PhoneEvent(String name, String caller) {}

    private record Capture(
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

        frame.setSize(970, 740);
        frame.setMinimumSize(new Dimension(750, 560));
        frame.setLocationRelativeTo(null);
        frame.getContentPane().setBackground(BG);
        frame.setLayout(new BorderLayout(12, 12));

        JPanel header = panel(BG);
        header.setBorder(new EmptyBorder(18, 20, 0, 20));
        header.setLayout(new BorderLayout());

        header.add(
                label("SNOM 320  ·  AI CALL", 22, true, FG),
                BorderLayout.WEST
        );

        JPanel badge = panel(BG);
        badge.setLayout(new BoxLayout(badge, BoxLayout.Y_AXIS));

        status.setForeground(GREEN);
        status.setFont(new Font("Segoe UI", Font.BOLD, 14));

        duration.setForeground(FG);
        duration.setFont(new Font("Consolas", Font.BOLD, 25));

        badge.add(status);
        badge.add(duration);

        header.add(badge, BorderLayout.EAST);
        frame.add(header, BorderLayout.NORTH);

        words.setText(
                "Сними трубку Snom 320. " +
                        "После завершения звонка текст будет отправлен в OpenAI.\n\n"
        );

        setupText(words);
        setupText(answer);

        answer.setText("Здесь будет ответ нейросети.\n");

        JPanel t1 = textPanel(
                "РАСПОЗНАННЫЙ ТЕКСТ", words
        );

        JPanel t2 = textPanel(
                "ОТВЕТ ИИ", answer
        );

        JSplitPane split = new JSplitPane(
                JSplitPane.VERTICAL_SPLIT,
                t1,
                t2
        );

        split.setResizeWeight(0.52);
        split.setDividerSize(8);
        split.setBorder(null);

        JPanel middle = panel(BG);
        middle.setBorder(new EmptyBorder(0, 20, 0, 20));
        middle.setLayout(new BorderLayout(0, 10));

        JPanel indicators = panel(CARD);

        indicators.setLayout(
                new BoxLayout(indicators, BoxLayout.Y_AXIS)
        );

        indicators.setBorder(
                new EmptyBorder(10, 14, 10, 14)
        );

        audioStatus.setForeground(FG);
        apiStatus.setForeground(SECONDARY);
        preview.setForeground(new Color(147, 197, 253));

        indicators.add(audioStatus);
        indicators.add(Box.createVerticalStrut(6));

        indicators.add(apiStatus);
        indicators.add(Box.createVerticalStrut(6));

        eventStatus.setForeground(SECONDARY);
        indicators.add(eventStatus);
        indicators.add(Box.createVerticalStrut(6));

        hookStatus.setForeground(SECONDARY);
        indicators.add(hookStatus);
        indicators.add(Box.createVerticalStrut(8));

        level.setPreferredSize(new Dimension(200, 10));

        level.setMaximumSize(
                new Dimension(Integer.MAX_VALUE, 10)
        );

        level.setForeground(GREEN);
        level.setBackground(BG);
        level.setBorderPainted(false);

        indicators.add(level);
        indicators.add(Box.createVerticalStrut(6));
        indicators.add(preview);

        middle.add(indicators, BorderLayout.NORTH);
        middle.add(split, BorderLayout.CENTER);

        frame.add(middle, BorderLayout.CENTER);

        JPanel footer = panel(BG);
        footer.setBorder(new EmptyBorder(0, 20, 18, 20));
        footer.setLayout(new BorderLayout(0, 10));

        JPanel buttons = panel(BG);
        buttons.setLayout(new FlowLayout(FlowLayout.LEFT, 8, 0));

        JButton copy = button("Копировать запрос");

        copy.addActionListener(e ->
                copyClipboard(
                        lastPrompt,
                        "Текст ещё не распознан."
                )
        );

        JButton send = button("Отправить повторно");

        send.addActionListener(e ->
                sendToOpenAi(
                        lastPrompt,
                        "manual_" + STAMP.format(LocalDateTime.now()),
                        true
                )
        );

        JButton folder = button("Открыть историю");

        folder.addActionListener(e -> {
            try {
                Files.createDirectories(HISTORY);

                Desktop.getDesktop().open(
                        HISTORY.toAbsolutePath().toFile()
                );

            } catch (Exception ex) {
                showError(ex.getMessage());
            }
        });

        buttons.add(copy);
        buttons.add(send);
        buttons.add(folder);

        JButton end = button("Завершить вручную");

        end.addActionListener(e ->
                events.offer(
                        new PhoneEvent("ended", "кнопка")
                )
        );

        buttons.add(end);
        footer.add(buttons, BorderLayout.NORTH);

        JPanel settings = panel(BG);

        settings.setLayout(
                new FlowLayout(FlowLayout.LEFT, 20, 0)
        );

        automatic.setForeground(FG);
        automatic.setOpaque(false);

        automatic.addActionListener(e ->
                autoSend = automatic.isSelected()
        );

        testMode.setForeground(FG);
        testMode.setOpaque(false);

        testMode.addActionListener(e ->
                events.offer(
                        new PhoneEvent(
                                testMode.isSelected()
                                        ? "test_start"
                                        : "test_end",
                                "тест"
                        )
                )
        );

        settings.add(automatic);
        settings.add(testMode);

        footer.add(settings, BorderLayout.SOUTH);
        frame.add(footer, BorderLayout.SOUTH);

        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                close();
            }
        });

        timer = new javax.swing.Timer(
                250,
                e -> updateTimer()
        );

        timer.start();

        apiStatus.setText(
                apiKey == null || apiKey.isBlank()
                        ? "OpenAI API: ключ OPENAI_API_KEY не найден"
                        : "OpenAI API: ключ найден · модель " + MODEL_NAME
        );

        frame.setVisible(true);

        startEventServer();
        startSnomHookServer();

        Thread worker = new Thread(
                this::capture,
                "vosk-capture"
        );

        worker.setDaemon(true);
        worker.start();
    }

    private static JPanel panel(Color bg) {
        JPanel p = new JPanel();
        p.setBackground(bg);
        return p;
    }

    private static JLabel label(
            String text,
            int size,
            boolean bold,
            Color color
    ) {
        JLabel l = new JLabel(text);

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

    private static JButton button(String title) {
        JButton b = new JButton(title);

        b.setFont(
                new Font("Segoe UI", Font.BOLD, 12)
        );

        b.setFocusPainted(false);
        b.setForeground(FG);
        b.setBackground(new Color(51, 65, 85));

        return b;
    }

    private static void setupText(JTextArea text) {
        text.setFont(
                new Font("Segoe UI", Font.PLAIN, 17)
        );

        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setEditable(false);
        text.setBackground(CARD);
        text.setForeground(FG);
        text.setCaretColor(FG);

        text.setBorder(
                new EmptyBorder(8, 6, 8, 6)
        );
    }

    private static JPanel textPanel(
            String title,
            JTextArea text
    ) {
        JPanel root = panel(CARD);

        root.setBorder(
                new EmptyBorder(10, 12, 10, 12)
        );

        root.setLayout(new BorderLayout(0, 6));

        root.add(
                label(title, 13, true, SECONDARY),
                BorderLayout.NORTH
        );

        JScrollPane scroll = new JScrollPane(text);

        scroll.setBorder(null);
        scroll.getViewport().setBackground(CARD);

        root.add(scroll, BorderLayout.CENTER);

        return root;
    }

    private void startEventServer() {
        try {
            socket = new DatagramSocket(
                    new InetSocketAddress(
                            "127.0.0.1",
                            EVENT_PORT
                    )
            );

            Thread listener = new Thread(() -> {
                byte[] buf = new byte[2048];

                while (!closing) {
                    try {
                        DatagramPacket p =
                                new DatagramPacket(
                                        buf,
                                        buf.length
                                );

                        socket.receive(p);

                        String raw = new String(
                                p.getData(),
                                p.getOffset(),
                                p.getLength(),
                                StandardCharsets.UTF_8
                        );

                        String[] parts = raw.split("\\|", 2);

                        String name = parts[0]
                                .trim()
                                .toLowerCase(Locale.ROOT);

                        String caller = parts.length > 1
                                ? parts[1].trim()
                                : "";

                        if (name.equals("incoming") ||
                                name.equals("connected") ||
                                name.equals("ended")) {

                            events.offer(
                                    new PhoneEvent(name, caller)
                            );
                        }

                    } catch (Exception ex) {
                        if (!closing) {
                            ui(() -> audioStatus.setText(
                                    "Ошибка событий: " +
                                            ex.getMessage()
                            ));
                        }
                    }
                }

            }, "microsip-events");

            listener.setDaemon(true);
            listener.start();

        } catch (SocketException ex) {
            showError(
                    "UDP-порт " + EVENT_PORT +
                            " занят. Закрой другую копию программы."
            );
        }
    }

    private void startSnomHookServer() {
        try {
            snomHookServer = new ServerSocket();

            snomHookServer.bind(
                    new InetSocketAddress(
                            PC_IP,
                            SNOM_HOOK_PORT
                    )
            );

            hookStatus.setText(
                    "Snom: HTTP " +
                            PC_IP + ":" + SNOM_HOOK_PORT + " готов"
            );

            Thread listener = new Thread(() -> {
                while (!closing) {
                    try (Socket client = snomHookServer.accept()) {
                        client.setSoTimeout(2500);

                        String remote = client.getInetAddress()
                                .getHostAddress();

                        boolean allowed =
                                remote.equals(SNOM_IP) ||
                                        remote.equals(PC_IP);

                        BufferedReader in = new BufferedReader(
                                new InputStreamReader(
                                        client.getInputStream(),
                                        StandardCharsets.US_ASCII
                                )
                        );

                        String request = in.readLine();

                        boolean hangup = request != null &&
                                (
                                        request.startsWith("GET /hangup ") ||
                                                request.startsWith("GET /hangup?")
                                );

                        int code = !allowed
                                ? 403
                                : hangup ? 200 : 404;

                        byte[] response = (
                                code == 200 ? "OK" : "Not found"
                        ).getBytes(StandardCharsets.UTF_8);

                        String headers =
                                "HTTP/1.1 " +
                                        code + " " +
                                        (code == 200 ? "OK" : "Error") +
                                        "\r\nContent-Type: text/plain; charset=utf-8\r\n" +
                                        "Content-Length: " + response.length +
                                        "\r\nConnection: close\r\n\r\n";

                        client.getOutputStream().write(
                                headers.getBytes(
                                        StandardCharsets.US_ASCII
                                )
                        );

                        client.getOutputStream().write(response);
                        client.getOutputStream().flush();

                        if (code == 200) {
                            events.offer(
                                    new PhoneEvent(
                                            "ended",
                                            "Snom " + remote
                                    )
                            );

                            ui(() -> hookStatus.setText(
                                    "Snom: получен сигнал укладывания трубки"
                            ));
                        }

                    } catch (Exception e) {
                        if (!closing) {
                            ui(() -> hookStatus.setText(
                                    "Snom HTTP: " + e.getMessage()
                            ));
                        }
                    }
                }

            }, "snom-hook");

            listener.setDaemon(true);
            listener.start();

        } catch (IOException e) {
            hookStatus.setText(
                    "Snom HTTP недоступен: " + e.getMessage()
            );
        }
    }

    private void processEvents(Recognizer rec) {
        PhoneEvent e;

        while ((e = events.poll()) != null) {
            PhoneEvent current = e;

            ui(() -> eventStatus.setText(
                    "Событие: " + current.name() +
                            (current.caller().isBlank()
                                    ? ""
                                    : " · " + current.caller())
            ));

            switch (e.name()) {
                case "incoming" -> {
                    if (activeSession &&
                            !testSession &&
                            finishAt == 0) {

                        stoppedDuration =
                                System.nanoTime() - startedAt;

                        finishCall(rec);
                        rec.reset();
                    }

                    ui(() -> {
                        if (!connected) {
                            status.setText(
                                    "Входящий вызов на 101"
                            );
                        }
                    });
                }

                case "connected" -> beginCall(rec, false);

                case "test_start" -> beginCall(rec, true);

                case "ended", "test_end" -> {
                    if (activeSession) {
                        connected = false;

                        stoppedDuration =
                                System.nanoTime() - startedAt;

                        finishAt = System.nanoTime() +
                                HANGUP_GRACE_NS;

                        ui(() -> status.setText(
                                "Завершение распознавания..."
                        ));
                    }
                }

                default -> {
                }
            }
        }
    }

    private void beginCall(
            Recognizer rec,
            boolean test
    ) {
        if (activeSession && finishAt == 0) {
            return;
        }

        if (activeSession) {
            finishCall(rec);
        }

        rec.reset();

        TargetDataLine existing = audioLine;

        if (existing != null) {
            existing.flush();
        }

        activeSession = true;
        testSession = test;
        finishAt = 0;

        connected = true;
        startedAt = System.nanoTime();
        stoppedDuration = 0;

        recognized.setLength(0);

        callFile = HISTORY.resolve(
                STAMP.format(LocalDateTime.now()) +
                        "_call.txt"
        );

        ui(() -> {
            status.setText(
                    test
                            ? "Тестовое распознавание"
                            : "● Разговор активен"
            );

            words.setText("");
            preview.setText("Слушаю трубку...");
            level.setValue(0);
        });
    }

    private void finishCall(Recognizer rec) {
        if (!activeSession) {
            return;
        }

        addWords(rec.getFinalResult());

        activeSession = false;
        connected = false;
        finishAt = 0;

        String prompt = recognized.toString().trim();

        String sessionId = callFile.getFileName()
                .toString()
                .replace("_call.txt", "");

        boolean send = autoSend && !testSession;

        ui(() -> {
            lastPrompt = prompt;

            preview.setText(" ");
            level.setValue(0);
            status.setText("Звонок завершён");

            if (prompt.isEmpty()) {
                apiStatus.setText(
                        "Текст не распознан — отправка пропущена"
                );
                return;
            }

            copyClipboard(prompt, null);

            if (send) {
                sendToOpenAi(prompt, sessionId, false);
            } else {
                apiStatus.setText(
                        "Текст скопирован. Автоотправка отключена."
                );
            }
        });
    }

    private void capture() {
        Path modelPath = Path.of("model");

        if (!Files.isDirectory(modelPath.resolve("am"))) {
            ui(() -> showError(
                    "Модель Vosk не найдена. " +
                            "Проверь папку model рядом с pom.xml."
            ));
            return;
        }

        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS);

            try (
                    Model model = new Model(
                            modelPath.toString()
                    );

                    Recognizer rec = new Recognizer(
                            model, VOSK_RATE
                    )
            ) {
                Capture c = openCable();
                audioLine = c.line();

                try {
                    c.line().start();

                    ui(() -> audioStatus.setText(
                            "VB-CABLE подключён: " +
                                    c.name() + " · " +
                                    (int) c.format().getSampleRate() +
                                    " Hz"
                    ));

                    Resampler resampler = new Resampler(
                            c.format().getSampleRate(),
                            c.format().getChannels()
                    );

                    byte[] input = new byte[8192];
                    byte[] output = new byte[16384];

                    long nextPreview = 0;
                    long nextVolume = 0;

                    int frameBytes = c.format().getFrameSize();

                    while (!closing) {
                        processEvents(rec);

                        if (activeSession &&
                                finishAt != 0 &&
                                System.nanoTime() >= finishAt) {

                            finishCall(rec);
                            rec.reset();
                            resampler.reset();
                        }

                        int available = c.line().available();

                        int count = Math.min(
                                available,
                                input.length
                        );

                        count -= count % frameBytes;

                        if (count < frameBytes) {
                            Thread.sleep(15);
                            continue;
                        }

                        int size = c.line().read(
                                input, 0, count
                        );

                        if (size <= 0 || !activeSession) {
                            continue;
                        }

                        long now = System.nanoTime();

                        if (now >= nextVolume) {
                            int value = volume(
                                    input,
                                    size,
                                    c.format().getChannels()
                            );

                            ui(() -> level.setValue(value));

                            nextVolume =
                                    now + 100_000_000L;
                        }

                        int n = resampler.convert(
                                input,
                                size,
                                output
                        );

                        if (n > 0) {
                            if (rec.acceptWaveForm(output, n)) {
                                addWords(rec.getResult());

                                ui(() -> preview.setText(
                                        "Слушаю..."
                                ));

                            } else if (now >= nextPreview) {
                                String partial = value(
                                        rec.getPartialResult(),
                                        "partial"
                                );

                                ui(() -> preview.setText(
                                        partial.isBlank()
                                                ? "Слушаю..."
                                                : "Слышу: " + partial
                                ));

                                nextPreview =
                                        now + 250_000_000L;
                            }
                        }
                    }

                } finally {
                    c.line().stop();
                    c.line().close();
                }
            }

        } catch (Exception ex) {
            if (!closing) {
                ui(() -> showError(
                        "Ошибка захвата/распознавания: " +
                                ex.getMessage()
                ));
            }
        }
    }

    private void addWords(String json) {
        String text = value(json, "text");

        if (text.isBlank()) {
            return;
        }

        if (!recognized.isEmpty()) {
            recognized.append(' ');
        }

        recognized.append(text);

        String line =
                CLOCK.format(LocalDateTime.now()) +
                        " | " + text;

        try {
            Files.createDirectories(HISTORY);

            Files.writeString(
                    callFile,
                    line + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );

            Files.writeString(
                    Path.of("transcript.txt"),
                    line + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );

        } catch (IOException ex) {
            ui(() -> audioStatus.setText(
                    "Ошибка сохранения: " +
                            ex.getMessage()
            ));
        }

        ui(() -> {
            words.append(line + "\n\n");

            words.setCaretPosition(
                    words.getDocument().getLength()
            );
        });
    }

    private static String value(
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

    private void sendToOpenAi(
            String prompt,
            String sessionId,
            boolean manual
    ) {
        if (prompt == null || prompt.isBlank()) {
            apiStatus.setText(
                    "Нечего отправлять: текст пустой"
            );
            return;
        }

        if (apiKey == null || apiKey.isBlank()) {
            apiStatus.setText(
                    "Нужен OPENAI_API_KEY (отдельный API-ключ)"
            );
            return;
        }

        if (creditsExhausted && !manual) {
            apiStatus.setText(
                    "API: нет кредитов. Текст сохранён и скопирован; " +
                            "автоотправка приостановлена."
            );
            return;
        }

        apiStatus.setText(
                "Отправляю запрос в OpenAI..."
        );

        answer.append(
                "\n[" +
                        CLOCK.format(LocalDateTime.now()) +
                        "] Запрос: " + prompt + "\n"
        );

        answer.setCaretPosition(
                answer.getDocument().getLength()
        );

        apiWorker.execute(() -> {
            try {
                ObjectNode request =
                        JSON.createObjectNode();

                request.put("model", MODEL_NAME);

                request.put(
                        "instructions",
                        "Ты полезный голосовой помощник. " +
                                "Отвечай на русском языке, " +
                                "чётко и по существу. " +
                                "Текст пользователя получен " +
                                "распознаванием речи. Понимай его по смыслу."
                );

                request.put("input", prompt);
                request.put("store", false);
                request.put("max_output_tokens", 1100);

                HttpRequest httpRequest =
                        HttpRequest.newBuilder(
                                        URI.create(
                                                "https://api.openai.com/v1/responses"
                                        )
                                )
                                .header(
                                        "Authorization",
                                        "Bearer " + apiKey
                                )
                                .header(
                                        "Content-Type",
                                        "application/json"
                                )
                                .timeout(Duration.ofSeconds(90))
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                JSON.writeValueAsString(request),
                                                StandardCharsets.UTF_8
                                        )
                                )
                                .build();

                HttpResponse<String> response =
                        http.send(
                                httpRequest,
                                HttpResponse.BodyHandlers.ofString(
                                        StandardCharsets.UTF_8
                                )
                        );

                JsonNode root =
                        JSON.readTree(response.body());

                if (response.statusCode() / 100 != 2) {
                    JsonNode error = root.path("error");

                    String code = error.path("code")
                            .asText("");

                    String message = error.path("message")
                            .asText(
                                    "HTTP " + response.statusCode()
                            );

                    String lower =
                            message.toLowerCase(Locale.ROOT);

                    if (response.statusCode() == 429 &&
                            (
                                    code.contains("credit") ||
                                            code.contains("quota") ||
                                            lower.contains("no credits") ||
                                            lower.contains("quota")
                            )) {

                        creditsExhausted = true;

                        ui(() -> {
                            apiStatus.setText(
                                    "Нет кредитов OpenAI API. " +
                                            "Текст сохранён и скопирован."
                            );

                            answer.append(
                                    "\n[API: закончились кредиты.]\n"
                            );
                        });

                        return;
                    }

                    throw new IOException(
                            "HTTP " +
                                    response.statusCode() +
                                    ": " + message
                    );
                }

                creditsExhausted = false;

                StringBuilder out = new StringBuilder();

                for (JsonNode item : root.path("output")) {
                    if (!"message".equals(
                            item.path("type").asText()
                    )) {
                        continue;
                    }

                    for (JsonNode piece : item.path("content")) {
                        if ("output_text".equals(
                                piece.path("type").asText()
                        )) {
                            if (!out.isEmpty()) {
                                out.append('\n');
                            }

                            out.append(
                                    piece.path("text").asText("")
                            );
                        }
                    }
                }

                String result = out.toString().trim();

                if (result.isEmpty()) {
                    throw new IOException(
                            "Модель не вернула текст. Статус: " +
                                    root.path("status")
                                            .asText("неизвестен")
                    );
                }

                Files.createDirectories(HISTORY);

                Files.writeString(
                        HISTORY.resolve(
                                sessionId + "_answer.txt"
                        ),
                        result,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING
                );

                ui(() -> {
                    apiStatus.setText(
                            "Ответ получен · " + MODEL_NAME
                    );

                    answer.append(
                            "\nИИ: " + result + "\n\n"
                    );

                    answer.setCaretPosition(
                            answer.getDocument().getLength()
                    );
                });

            } catch (Exception ex) {
                ui(() -> {
                    apiStatus.setText(
                            "Ошибка API: " +
                                    ex.getMessage()
                    );

                    answer.append(
                            "\n[Ошибка запроса: " +
                                    ex.getMessage() +
                                    "]\n"
                    );
                });
            }
        });
    }

    private void updateTimer() {
        long nanos = connected
                ? System.nanoTime() - startedAt
                : stoppedDuration;

        long seconds = Math.max(
                0,
                nanos / 1_000_000_000L
        );

        duration.setText(
                String.format(
                        Locale.ROOT,
                        "%02d:%02d",
                        seconds / 60,
                        seconds % 60
                )
        );
    }

    private void copyClipboard(
            String text,
            String emptyMessage
    ) {
        if (text == null || text.isBlank()) {
            if (emptyMessage != null) {
                apiStatus.setText(emptyMessage);
            }
            return;
        }

        try {
            Toolkit.getDefaultToolkit()
                    .getSystemClipboard()
                    .setContents(
                            new StringSelection(text),
                            null
                    );

            apiStatus.setText(
                    "Текст скопирован в буфер обмена"
            );

        } catch (IllegalStateException ex) {
            apiStatus.setText(
                    "Буфер занят. Нажми «Копировать запрос» ещё раз."
            );
        }
    }

    private static Capture openCable()
            throws LineUnavailableException {

        List<Mixer.Info> mixers = new ArrayList<>();

        for (Mixer.Info info :
                AudioSystem.getMixerInfo()) {

            String name = info.getName()
                    .toLowerCase(Locale.ROOT);

            if (name.contains("cable output") ||
                    name.contains("vb-audio virtual cable")) {

                mixers.add(info);
            }
        }

        AudioFormat[] options = {
                new AudioFormat(48000, 16, 2, true, false),
                new AudioFormat(48000, 16, 1, true, false),
                new AudioFormat(44100, 16, 2, true, false),
                new AudioFormat(44100, 16, 1, true, false),
                new AudioFormat(16000, 16, 1, true, false)
        };

        for (Mixer.Info mixer : mixers) {
            for (AudioFormat fmt : options) {
                try {
                    TargetDataLine line =
                            AudioSystem.getTargetDataLine(
                                    fmt,
                                    mixer
                            );

                    line.open(fmt, 16384);

                    return new Capture(
                            line,
                            fmt,
                            mixer.getName()
                    );

                } catch (
                        LineUnavailableException |
                        IllegalArgumentException ignored
                ) {
                }
            }
        }

        throw new LineUnavailableException(
                "Не найден CABLE Output (VB-Audio Virtual Cable)"
        );
    }

    private static int volume(
            byte[] samples,
            int bytes,
            int channels
    ) {
        double sum = 0;
        int frames = 0;

        for (int i = 0;
             i + 2 * channels <= bytes;
             i += 2 * channels) {

            int v = (short) (
                    (samples[i] & 255) |
                            (samples[i + 1] << 8)
            );

            sum += (double) v * v;
            frames++;
        }

        return Math.min(
                100,
                (int) (
                        Math.sqrt(
                                sum / Math.max(frames, 1)
                        ) / 110
                )
        );
    }

    private static final class Resampler {
        private final int channels;
        private final double ratio;
        private double remaining;
        private double sum;

        Resampler(float inputRate, int channels) {
            this.channels = channels;
            this.ratio = inputRate / VOSK_RATE;
            reset();
        }

        void reset() {
            remaining = ratio;
            sum = 0;
        }

        int convert(
                byte[] src,
                int count,
                byte[] out
        ) {
            int pos = 0;
            int frameBytes = channels * 2;

            for (int i = 0;
                 i + frameBytes <= count;
                 i += frameBytes) {

                double sample = 0;

                for (int ch = 0;
                     ch < channels;
                     ch++) {

                    int x = i + ch * 2;

                    sample += (short) (
                            (src[x] & 255) |
                                    (src[x + 1] << 8)
                    );
                }

                sample /= channels;
                double available = 1;

                while (available > 1e-9) {
                    double part = Math.min(
                            remaining,
                            available
                    );

                    sum += sample * part;
                    remaining -= part;
                    available -= part;

                    if (remaining < 1e-9) {
                        int n = Math.max(
                                -32768,
                                Math.min(
                                        32767,
                                        (int) Math.round(
                                                sum / ratio
                                        )
                                )
                        );

                        out[pos++] = (byte) n;
                        out[pos++] = (byte) (n >>> 8);

                        remaining = ratio;
                        sum = 0;
                    }
                }
            }

            return pos;
        }
    }

    private void showError(String msg) {
        JOptionPane.showMessageDialog(
                frame,
                msg,
                "Snom Transcriber",
                JOptionPane.ERROR_MESSAGE
        );
    }

    private static void ui(Runnable fn) {
        SwingUtilities.invokeLater(fn);
    }

    private void close() {
        closing = true;

        if (socket != null) {
            socket.close();
        }

        if (snomHookServer != null) {
            try {
                snomHookServer.close();
            } catch (IOException ignored) {
            }
        }

        if (audioLine != null) {
            audioLine.close();
        }

        if (timer != null) {
            timer.stop();
        }

        apiWorker.shutdownNow();
        frame.dispose();
    }
}