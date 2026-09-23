package it.gioco31.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.CloseReason;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.Session;

import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Un browser di test: un cookie jar suo (quindi una HttpSession sua) più i
 * WebSocket aperti con quella sessione. Serve averne due distinti per
 * verificare i broadcast, perché tutta l'identità del giocatore sta nella
 * HttpSession creata dal join.
 */
final class TestBrowser implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    private final EmbeddedApp app;
    private final CookieManager cookies = new CookieManager();
    private final HttpClient http;
    private final List<Socket> sockets = new ArrayList<>();

    TestBrowser(EmbeddedApp app) {
        this.app = app;
        this.http = HttpClient.newBuilder()
                .cookieHandler(cookies)
                .followRedirects(HttpClient.Redirect.NEVER) // il redirect è parte di ciò che verifichiamo
                .connectTimeout(HTTP_TIMEOUT)
                .build();
    }

    /** Crea una stanza e ci si siede come host. @return il roomId assegnato. */
    String createRoom(String name, int players, int lives) throws Exception {
        String location = postForm("/join",
                "action=create&name=" + enc(name) + "&players=" + players + "&lives=" + lives);
        return roomIdFrom(location);
    }

    /** Si siede in una stanza esistente. */
    void joinRoom(String name, String roomId) throws Exception {
        String location = postForm("/join", "action=join&name=" + enc(name) + "&room=" + roomId);
        assertEquals(roomId, roomIdFrom(location), "il join deve portare nella stanza chiesta");
    }

    /** Avvia la partita (ha effetto solo se questo browser è l'host). */
    void startMatch(String roomId) throws Exception {
        postForm("/start", "room=" + roomId);
    }

    /** GET che deve andare a buon fine: torna il corpo della risposta. */
    String getPage(String path) throws Exception {
        HttpResponse<String> resp = get(path);
        assertEquals(200, resp.statusCode(), "GET " + path);
        return resp.body();
    }

    int statusOf(String path) throws Exception {
        return get(path).statusCode();
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(app.httpUrl(path)))
                .timeout(HTTP_TIMEOUT)
                .GET()
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * POST form-encoded. Il flusso felice risponde sempre 302: un 200 significa
     * che il servlet ha renderizzato la pagina d'errore.
     */
    private String postForm(String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(app.httpUrl(path)))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(HTTP_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(302, resp.statusCode(),
                "POST " + path + " doveva reindirizzare, ha risposto " + resp.statusCode());
        return resp.headers().firstValue("Location").orElseThrow(
                () -> new IllegalStateException("redirect senza Location per POST " + path));
    }

    private static String roomIdFrom(String location) {
        int i = location.indexOf("room=");
        assertNotNull(location);
        if (i < 0) throw new IllegalStateException("nessun roomId nel redirect: " + location);
        return location.substring(i + "room=".length());
    }

    /** Apre un WebSocket sulla stanza, portandosi dietro il cookie di sessione. */
    Socket openSocket(String roomId) throws Exception {
        Socket socket = new Socket();
        ClientEndpointConfig config = ClientEndpointConfig.Builder.create()
                .configurator(new ClientEndpointConfig.Configurator() {
                    @Override
                    public void beforeRequest(Map<String, List<String>> headers) {
                        String cookie = sessionCookie();
                        if (cookie != null) headers.put("Cookie", List.of(cookie));
                    }
                })
                .build();

        socket.session = ContainerProvider.getWebSocketContainer()
                .connectToServer(socket, config, URI.create(app.wsUrl("/ws/" + roomId)));
        sockets.add(socket);
        return socket;
    }

    /** Il JSESSIONID così com'è, o null se questo browser non ha ancora una sessione. */
    private String sessionCookie() {
        for (HttpCookie c : cookies.getCookieStore().getCookies()) {
            if ("JSESSIONID".equals(c.getName())) return c.getName() + "=" + c.getValue();
        }
        return null;
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        for (Socket s : sockets) s.close();
        sockets.clear();
    }

    /** Un WebSocket aperto, con la coda dei messaggi ricevuti. */
    static final class Socket extends Endpoint implements AutoCloseable {

        private static final long WAIT_SECONDS = 10;

        private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final CountDownLatch closed = new CountDownLatch(1);
        private volatile CloseReason closeReason;
        private volatile Session session;

        @Override
        public void onOpen(Session session, EndpointConfig config) {
            this.session = session;
            session.addMessageHandler(String.class, messages::add);
        }

        @Override
        public void onClose(Session session, CloseReason reason) {
            this.closeReason = reason;
            closed.countDown();
        }

        /** Manda un'azione nello stesso formato di js/net.js: JSON. */
        void send(String action) throws Exception {
            session.getBasicRemote().sendText("{\"action\":\"" + action + "\"}");
        }

        void send(String action, long arg) throws Exception {
            session.getBasicRemote().sendText(
                    "{\"action\":\"" + action + "\",\"arg\":" + arg + "}");
        }

        /** Prossimo stato ricevuto, già decodificato. Fallisce se non arriva. */
        JsonNode nextState() throws Exception {
            String json = messages.poll(WAIT_SECONDS, TimeUnit.SECONDS);
            assertNotNull(json, "nessuno stato ricevuto entro " + WAIT_SECONDS + " s");
            return MAPPER.readTree(json);
        }

        /**
         * Primo stato con {@code stateSeq} oltre quella indicata. Gli stati
         * precedenti (l'ingresso degli altri giocatori, per esempio) vengono
         * scartati: interessa il primo che riflette l'azione appena fatta.
         */
        JsonNode nextStateAfter(long stateSeq) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
            while (System.nanoTime() < deadline) {
                String json = messages.poll(WAIT_SECONDS, TimeUnit.SECONDS);
                if (json == null) break;
                JsonNode state = MAPPER.readTree(json);
                if (state.get("stateSeq").asLong() > stateSeq) return state;
            }
            throw new AssertionError("nessuno stato oltre stateSeq=" + stateSeq
                    + " entro " + WAIT_SECONDS + " s");
        }

        /**
         * Primo stato che riporta la fase indicata. Gli stati che arrivano
         * prima vengono scartati: al momento della chiamata può esserci ancora
         * in coda l'arrivo di un altro giocatore, e non è quello che si sta
         * aspettando.
         */
        JsonNode nextStateWithPhase(String phase) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
            while (System.nanoTime() < deadline) {
                String json = messages.poll(WAIT_SECONDS, TimeUnit.SECONDS);
                if (json == null) break;
                JsonNode state = MAPPER.readTree(json);
                if (phase.equals(state.path("phase").asText())) return state;
            }
            throw new AssertionError("nessuno stato in fase " + phase
                    + " entro " + WAIT_SECONDS + " s");
        }

        boolean awaitClose() throws InterruptedException {
            return closed.await(WAIT_SECONDS, TimeUnit.SECONDS);
        }

        CloseReason closeReason() {
            return closeReason;
        }

        boolean isOpen() {
            return session != null && session.isOpen();
        }

        @Override
        public void close() {
            try {
                if (session != null && session.isOpen()) session.close();
            } catch (Exception ignored) {
                // chiusura best-effort a fine test
            }
        }
    }
}
