package dev.toxido.anticheat.agent;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Ajan tarafindaki IPC istemcisi: uygulamanin actigi loopback portuna baglanir,
 * el sikismasi yapar ve duzenli kalp atisi gonderir.
 */
public final class AgentClient implements Runnable {
    private final int port;
    private final String token;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicInteger heartbeatStarted = new AtomicInteger(0);

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
                    startHeartbeat();
                }
            }
        } catch (Exception exception) {
            AgentMain.log("Baglanti kapandi: " + exception.getMessage());
        } finally {
            running.set(false);
        }
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
        String hello = "{\"type\":\"hello\",\"token\":\"" + escape(token) + "\",\"pid\":"
            + ProcessHandle.current().pid()
            + ",\"agentVersion\":\"" + AgentMain.VERSION + "\"}";
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
            return;
        }
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

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
