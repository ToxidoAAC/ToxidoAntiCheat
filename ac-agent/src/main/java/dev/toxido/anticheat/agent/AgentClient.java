package dev.toxido.anticheat.agent;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Ajan tarafindaki IPC istemcisi: uygulamanin actigi loopback portuna baglanir,
 * el sikismasi yapar, kalp atisi ve bulgu olaylari gonderir.
 */
public final class AgentClient implements Runnable {
    private final int port;
    private final String token;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicInteger heartbeatStarted = new AtomicInteger(0);
    private final Deque<String> pending = new ArrayDeque<>();

    private volatile BufferedWriter out;

    public AgentClient(int port, String token) {
        this.port = port;
        this.token = token;
    }

    @Override
    public void run() {
        Socket connected = connect();
        if (connected == null) {
            AgentMain.log("Uygulamaya baglanilamadi (port=" + port + ")");
            return;
        }

        try (Socket socket = connected;
             BufferedReader in = new BufferedReader(
                 new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {

            this.out = new BufferedWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

            sendHello();

            String line;
            while ((line = in.readLine()) != null) {
                if (line.contains("\"type\":\"ping\"")) {
                    send("{\"type\":\"pong\"}");
                } else if (line.contains("\"type\":\"hello_ack\"")) {
                    flushPending();
                    startHeartbeat();
                    AgentMain.onConnected(this);
                }
            }
        } catch (Exception exception) {
            AgentMain.log("Baglanti kapandi: " + exception.getMessage());
        } finally {
            running.set(false);
        }
    }

    public void sendCapability(String payloadJson) {
        send("{\"type\":\"capability\",\"payload\":" + payloadJson + "}");
    }

    public void sendInjection(InjectionFinding finding) {
        String payload = "{"
            + "\"kind\":" + Json.quote(finding.kind)
            + ",\"name\":" + Json.quote(finding.name)
            + ",\"path\":" + Json.quote(finding.path)
            + ",\"sha256\":" + Json.quote(finding.sha256)
            + ",\"signature\":" + Json.quote(finding.signature)
            + ",\"description\":" + Json.quote(finding.description)
            + ",\"confidence\":" + Json.quote(finding.confidence)
            + ",\"evidence\":" + Json.quote(finding.evidence)
            + ",\"exists\":" + finding.exists
            + ",\"deleted\":" + finding.deleted
            + "}";
        sendEvent("injection", finding.key, payload);
    }

    public void sendTrigger(TriggerEvent event) {
        String payload = "{"
            + "\"verdict\":" + Json.quote(event.verdict)
            + ",\"reason\":" + Json.quote(event.reason)
            + ",\"attack_time_ms\":" + event.attackTimeMs
            + ",\"input_age_ms\":" + event.inputAgeMs
            + ",\"physical_click\":" + event.physicalClick
            + ",\"injected_click\":" + event.injectedClick
            + ",\"raw_click\":" + event.rawClick
            + ",\"target_type\":" + Json.quote(event.targetType)
            + ",\"target_name\":" + Json.quote(event.targetName)
            + ",\"target_uuid\":" + Json.quote(event.targetUuid)
            + ",\"hook\":" + Json.quote(event.hook)
            + ",\"evidence\":" + Json.quote(event.evidence)
            + "}";
        sendEvent("triggerbot", event.key, payload);
    }

    public void sendLog(String message) {
        String payload = "{\"message\":" + Json.quote(message)
            + ",\"source\":\"agent\"}";
        sendEvent("log", "agent", payload);
    }

    public void sendEvent(String event, String key, String payloadJson) {
        send("{\"type\":\"event\",\"event\":" + Json.quote(event)
            + ",\"key\":" + Json.quote(key)
            + ",\"payload\":" + payloadJson + "}");
    }

    private Socket connect() {
        for (int attempt = 0; attempt < 20 && running.get(); attempt++) {
            try {
                return new Socket(InetAddress.getLoopbackAddress(), port);
            } catch (Exception exception) {
                sleep(500);
            }
        }
        return null;
    }

    private void sendHello() {
        String hello = "{\"type\":\"hello\",\"token\":" + Json.quote(token)
            + ",\"pid\":" + ProcessHandle.current().pid()
            + ",\"agentVersion\":" + Json.quote(AgentMain.VERSION) + "}";
        send(hello);
        AgentMain.log("El sikismasi gonderildi.");
    }

    private void startHeartbeat() {
        if (heartbeatStarted.compareAndSet(0, 1)) {
            AgentMain.log("El sikismasi kabul edildi; kalp atisi basliyor.");
            Thread thread = new Thread(this::heartbeatLoop, "toxido-ac-agent-heartbeat");
            thread.setDaemon(true);
            thread.start();
        }
    }

    private void heartbeatLoop() {
        while (running.get()) {
            send("{\"type\":\"heartbeat\",\"ts\":" + System.currentTimeMillis() + "}");
            sleep(5000);
        }
    }

    private synchronized void send(String json) {
        if (out == null) {
            if (pending.size() < 200) {
                pending.addLast(json);
            }
            return;
        }
        writeLine(json);
    }

    private synchronized void flushPending() {
        while (!pending.isEmpty() && out != null) {
            writeLine(pending.removeFirst());
        }
    }

    private void writeLine(String json) {
        try {
            out.write(json);
            out.newLine();
            out.flush();
        } catch (Exception exception) {
            running.set(false);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}