package it.gioco31.ws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import it.gioco31.model.Card;
import it.gioco31.model.GameState;
import it.gioco31.model.Phase;
import it.gioco31.model.Player;
import it.gioco31.room.GameRoom;
import it.gioco31.room.RoomRepository;
import it.gioco31.service.GameLifecycle;

import jakarta.servlet.http.HttpSession;
import jakarta.websocket.CloseReason;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.Session;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Il canale di gioco. Protocollo JSON in entrambe le direzioni: dal client
 * arriva {@code {"action": "...", "arg": <numero opzionale>}}, dal server esce
 * lo stato completo della stanza, oppure {@code {"pong": true}} in risposta
 * all'heartbeat. Tutto ciò che non è conforme viene ignorato in silenzio,
 * senza chiudere la connessione.
 */
@ServerEndpoint(value = "/ws/{roomId}", configurator = HttpSessionConfigurator.class)
public class RoomEndpoint {

    private static final Logger LOG = Logger.getLogger(RoomEndpoint.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Risposta all'heartbeat: il client la riconosce da questo campo. */
    private static final String PONG_JSON = "{\"pong\":true}";

    /** Attesa massima per l'invio a un singolo client prima di considerarlo bloccato. */
    private static final long SEND_TIMEOUT_MS = 5_000L;

    /** Quante voci del registro mosse viaggiano in ogni stato. */
    private static final int EVENTS_IN_STATE = 15;

    /** Anti-abuso del canale: lunghezza massima e messaggi al secondo per giocatore. */
    private static final int MAX_MSG_LEN = 200;
    private static final int MAX_MSG_PER_SEC = 20;
    private static final long RATE_WINDOW_NANOS = 1_000_000_000L;

    /**
     * Finestra scorrevole O(1): conserva l'istante degli ultimi
     * MAX_MSG_PER_SEC messaggi ammessi. Se il più vecchio dei venti è entro
     * l'ultimo secondo, la soglia è superata.
     *
     * <p>Il tempo è {@code System.nanoTime()}, monotono: con l'orologio di
     * sistema una correzione NTP all'indietro renderebbe negativo il delta e
     * bloccherebbe *ogni* messaggio del giocatore finché la finestra non si
     * sblocca — e le voci rifiutate non invecchiano mai, perché non entrano
     * nel ring.
     *
     * <p>{@code allow} è sincronizzato: il container consegna i messaggi di una
     * sessione in ordine, ma non necessariamente sullo stesso thread, e la
     * pubblicazione via ConcurrentHashMap non basta a rendere visibili le
     * scritture successive sul ring.
     */
    static final class RateWindow { // package-private: testata direttamente
        private final long[] ring = new long[MAX_MSG_PER_SEC];
        private int idx = 0;
        private int filled = 0;

        synchronized boolean allow(long nowNanos) {
            // ring[idx] è il 20°-ultimo messaggio ammesso, finché il ring è pieno.
            if (filled == ring.length && nowNanos - ring[idx] < RATE_WINDOW_NANOS) return false;
            ring[idx] = nowNanos;
            idx = (idx + 1) % ring.length;
            if (filled < ring.length) filled++;
            return true;
        }
    }

    private static final Map<String, Set<Session>> ROOM_SESSIONS = new ConcurrentHashMap<>();

    /**
     * roomId -> (token -> finestra di frequenza). Il limite è per giocatore e
     * non per socket: nulla vieta a un client di aprire più WebSocket con lo
     * stesso token (la riconnessione ci conta sopra), quindi una finestra per
     * sessione si moltiplicherebbe per il numero di socket aperti — bastava
     * aprirne cinquanta per avere cinquanta volte la soglia.
     */
    private static final Map<String, Map<String, RateWindow>> RATE_WINDOWS = new ConcurrentHashMap<>();

    /**
     * Thread dedicato alle chiusure di sessione (vedi closeQuietly): sono
     * bloccanti e non devono mai avvenire sul thread di manutenzione.
     */
    private static final ExecutorService CLOSER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "gioco31-ws-closer");
        t.setDaemon(true);
        return t;
    });

    @OnOpen
    public void onOpen(Session ws, @PathParam("roomId") String roomId) throws IOException {
        String rid = RoomRepository.normalizeRoomId(roomId);

        GameRoom room = RoomRepository.get(rid);
        if (room == null) { ws.close(close("Room not found")); return; }

        // La HttpSession arriva dagli userProperties, che il container popola
        // per singola connessione a partire da quanto scritto dal configurator
        // durante l'handshake (vedi HttpSessionConfigurator).
        HttpSession http = (HttpSession) ws.getUserProperties().get(HttpSessionConfigurator.HTTP_SESSION_KEY);
        if (http == null) { ws.close(close("No HTTP session")); return; }

        String token = (String) http.getAttribute("playerToken");
        String sessionRoom = RoomRepository.normalizeRoomId((String) http.getAttribute("roomId"));
        if (token == null || sessionRoom == null || !rid.equals(sessionRoom)) {
            ws.close(close("Not joined"));
            return;
        }

        Integer me = room.indexByToken(token);
        if (me == null) { ws.close(close("Invalid player")); return; }

        ws.getUserProperties().put("token", token);
        addSession(rid, ws);
        room.markConnected(token);
        broadcast(rid, room);
    }

    private static void addSession(String rid, Session ws) {
        while (true) {
            Set<Session> set = ROOM_SESSIONS.computeIfAbsent(rid, k -> ConcurrentHashMap.newKeySet());
            set.add(ws);
            // onClose/broadcast possono aver rimosso il set (vuoto) subito dopo
            // computeIfAbsent: la sessione finirebbe in un set orfano, riprova.
            if (ROOM_SESSIONS.get(rid) == set) return;
            set.remove(ws);
        }
    }

    @OnMessage
    public void onMessage(Session ws, String msg, @PathParam("roomId") String roomId) {
        // Anti-abuso: un messaggio troppo lungo o oltre la frequenza consentita
        // viene ignorato in silenzio (niente chiusura, niente errore al client),
        // prima di prendere il lock della stanza o scatenare un broadcast.
        if (msg.length() > MAX_MSG_LEN) return;

        String rid = RoomRepository.normalizeRoomId(roomId);

        String token = (String) ws.getUserProperties().get("token");
        if (token == null) return;

        // La stanza va verificata prima del rate limit: altrimenti un messaggio
        // arrivato su una sessione la cui stanza è appena stata rimossa
        // ricreerebbe la sua voce in RATE_WINDOWS, che nessuno pulirebbe più.
        GameRoom room = RoomRepository.get(rid);
        if (room == null) return;

        if (!allowRate(rid, token)) return;

        Integer me = room.indexByToken(token);
        if (me == null) {
            closeQuietly(ws, "Invalid player");
            return;
        }

        JsonNode request = parseRequest(msg);
        if (request == null) return;

        String action = request.path("action").asText("");
        if (action.isEmpty()) return;

        // Il ping è solo heartbeat: segnala attività e si prende il suo pong.
        // Va servito prima del lock, perché touch() scrive un volatile e non ha
        // bisogno di esclusione, mentre quel lock serializza partite e broadcast
        // di tutta la stanza. E niente broadcast: 6 client con un ping ogni 20 s
        // produrrebbero altrimenti traffico e bump di stateSeq a vuoto.
        if ("ping".equals(action)) {
            room.touch();
            sendPong(ws);
            return;
        }

        JsonNode arg = request.path("arg");

        boolean changed;
        room.lock().lock();
        try {
            changed = applyAction(room, me, room.isHost(token), action, arg);
            room.touch();
        } finally {
            room.lock().unlock();
        }

        // Solo se lo stato è davvero cambiato: un'azione sconosciuta o respinta
        // da una guardia di fase/turno costava comunque un giro di lock, un JSON
        // per ogni sessione e un bump di stateSeq che faceva ridisegnare i client
        // per nulla.
        if (changed) broadcast(rid, room);
    }

    @OnClose
    public void onClose(Session ws, @PathParam("roomId") String roomId) {
        String rid = RoomRepository.normalizeRoomId(roomId);
        String token = (String) ws.getUserProperties().get("token");

        Set<Session> set = ROOM_SESSIONS.get(rid);
        if (set != null) {
            set.remove(ws);
            if (set.isEmpty()) ROOM_SESSIONS.remove(rid, set);
        }

        ws.getUserProperties().remove("token");

        if (token == null) return;
        GameRoom room = RoomRepository.get(rid);
        if (room == null) return;

        // Se era l'ultima connessione del giocatore, parte il periodo di grazia.
        if (!hasOpenSessionForToken(rid, token)) {
            dropRateWindow(rid, token);
            room.markDisconnected(token, System.currentTimeMillis());
        }
    }

    private static boolean allowRate(String rid, String token) {
        return RATE_WINDOWS
                .computeIfAbsent(rid, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(token, k -> new RateWindow())
                .allow(System.nanoTime());
    }

    /** La finestra vive finché il giocatore ha almeno un socket aperto. */
    private static void dropRateWindow(String rid, String token) {
        Map<String, RateWindow> byToken = RATE_WINDOWS.get(rid);
        if (byToken == null) return;
        byToken.remove(token);
        if (byToken.isEmpty()) RATE_WINDOWS.remove(rid, byToken);
    }

    private static boolean hasOpenSessionForToken(String rid, String token) {
        Set<Session> set = ROOM_SESSIONS.get(rid);
        if (set == null) return false;
        for (Session s : set) {
            if (s.isOpen() && token.equals(s.getUserProperties().get("token"))) return true;
        }
        return false;
    }

    /**
     * Messaggio del client: un oggetto JSON {@code {"action": "...", "arg": ...}}.
     * Qualsiasi altra cosa (JSON malformato, array, numero) viene ignorata in
     * silenzio, come tutto il resto dell'input non valido su questo canale.
     */
    private static JsonNode parseRequest(String msg) {
        try {
            JsonNode node = MAPPER.readTree(msg);
            return node.isObject() ? node : null;
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    /**
     * @return true se l'azione ha modificato lo stato della stanza, cioè se
     *         c'è qualcosa da ritrasmettere. Le uscite anticipate qui sotto
     *         sono tutte richieste che non lasciano traccia: niente broadcast.
     */
    private boolean applyAction(GameRoom room, int me, boolean isHost, String action, JsonNode arg) {
        GameState s = room.state();

        // Il ping non arriva mai fin qui: onMessage lo serve prima di prendere
        // il lock (è solo heartbeat, non tocca lo stato).

        if ("ackNotice".equals(action)) {
            if (!arg.canConvertToLong()) return false;
            return s.clearNoticeForPlayer(me, arg.longValue());
        }

        if ("restartGame".equals(action)) {
            if (s.getPhase() != Phase.GAME_OVER) return false;
            if (!isHost) return false;
            restartGame(room, s);
            return true;
        }

        if (me < 0 || me >= s.getPlayers().size()) return false;
        if (s.getPlayers().get(me).isEliminated()) return false;

        if (s.getPhase() == Phase.WAITING_FOR_PLAYERS) return false;
        if (s.getPhase() == Phase.GAME_OVER) return false;
        if (s.getCurrentIndex() != me) return false;

        return switch (action) {
            case "drawDeck"    -> applyOrNotify(s, me, () -> room.engine().drawPendingFromDeck(s));
            case "drawDiscard" -> applyOrNotify(s, me, () -> room.engine().drawPendingFromDiscard(s));
            case "keep" -> {
                if (!arg.canConvertToInt()) yield false;
                int idx = arg.intValue();
                yield applyOrNotify(s, me, () -> room.engine().keepPendingDraw(s, idx));
            }
            case "reject" -> applyOrNotify(s, me, () -> room.engine().rejectDraw(s));
            case "knock"   -> applyOrNotify(s, me, () -> room.engine().knock(s));
            default -> false;
        };
    }

    /**
     * Esegue una mossa; un rifiuto del motore diventa una notifica per il giocatore.
     * Torna sempre true: o la mossa è passata, o c'è un avviso nuovo da recapitare.
     */
    private static boolean applyOrNotify(GameState s, int me, Runnable move) {
        try {
            move.run();
        } catch (RuntimeException ex) {
            s.setNoticeForPlayer(me, ex.getMessage());
        }
        return true;
    }

    private void restartGame(GameRoom room, GameState s) {
        if (!GameLifecycle.startMatch(s, room.engine())) {
            s.setPhase(Phase.WAITING_FOR_PLAYERS);
        }
    }

    /** Ritrasmette lo stato a tutti i client della stanza (usato anche dallo sweeper). */
    public static void broadcastRoom(String roomId) {
        String rid = RoomRepository.normalizeRoomId(roomId);
        GameRoom room = RoomRepository.get(rid);
        if (room == null) return;
        try {
            broadcast(rid, room);
        } catch (RuntimeException ex) {
            LOG.log(Level.WARNING, "Broadcast fallito per la room " + rid, ex);
        }
    }

    /**
     * Ripulisce dal registro le sessioni di una stanza non più esistente,
     * invocata dallo sweeper dopo che RoomRepository ha rimosso la stanza.
     * Sta qui (e non in RoomRepository) per non creare una dipendenza
     * circolare tra repository ed endpoint: lo sweeper conosce entrambi.
     */
    public static void dropRoomSessions(String roomId) {
        String rid = RoomRepository.normalizeRoomId(roomId);
        RATE_WINDOWS.remove(rid);
        Set<Session> set = ROOM_SESSIONS.remove(rid);
        if (set == null) return;
        for (Session ws : set) {
            closeQuietly(ws, "Room not found");
        }
    }

    /** Una sessione col suo JSON già pronto, da inviare fuori dal lock. */
    private record Outbound(Session ws, String json) {}

    private static void broadcast(String rid, GameRoom room) {
        Set<Session> set = ROOM_SESSIONS.get(rid);
        if (set == null || set.isEmpty()) return;

        var snapshot = new ArrayList<>(set);
        var toRemove = new ArrayList<Session>();
        var toCloseInvalid = new ArrayList<Session>();
        var outbound = new ArrayList<Outbound>();

        // Un solo giro di lock per l'intera stanza: bumpSeq() una volta sola e
        // tutti i JSON costruiti dallo stesso istante dello stato. Così i viewer
        // ricevono la stessa stateSeq (snapshot atomico tra giocatori) e gli
        // invii, ordinati dalla sequenza, non possono più applicarsi fuori
        // ordine sul client.
        room.lock().lock();
        try {
            room.state().bumpSeq();
            for (Session ws : snapshot) {
                if (ws == null || !ws.isOpen()) {
                    toRemove.add(ws);
                    continue;
                }

                String token = (String) ws.getUserProperties().get("token");
                if (token == null) {
                    toRemove.add(ws);
                    continue;
                }

                Integer viewer = room.indexByToken(token);
                if (viewer == null) {
                    // La chiusura è I/O: rimandata fuori dal lock.
                    toRemove.add(ws);
                    toCloseInvalid.add(ws);
                    continue;
                }

                outbound.add(new Outbound(ws, buildStateJson(room.state(), viewer, room.isHost(token))));
            }
        } finally {
            room.lock().unlock();
        }

        // Invio fuori dal lock e non bloccante (vedi sendState). Un errore
        // sincrono qui (sessione chiusa nel frattempo) chiude la sessione:
        // l'onClose penserà a rimuoverla dal registro.
        for (Outbound o : outbound) {
            try {
                sendState(o.ws(), o.json());
            } catch (RuntimeException e) {
                LOG.log(Level.FINE, e, () -> "Invio stato fallito nella room " + rid + ", chiudo la sessione");
                closeQuietly(o.ws(), "IO error");
            }
        }

        for (Session ws : toCloseInvalid) {
            closeQuietly(ws, "Invalid player");
        }

        for (Session ws : toRemove) {
            if (ws != null) set.remove(ws);
        }

        if (set.isEmpty()) {
            ROOM_SESSIONS.remove(rid, set);
        }
    }

    /** Chiave della coda di invio negli userProperties della sessione. */
    private static final String SENDER_KEY = "stateSender";

    /**
     * Invio serializzato per sessione. L'async remote di Tomcat <b>non</b>
     * accoda: un secondo {@code sendText} mentre il primo è ancora in
     * scrittura solleva {@code IllegalStateException} ("remote endpoint was in
     * state [TEXT_FULL_WRITING]"), e il chiamante finiva per chiudere un client
     * sanissimo come se avesse un errore di I/O. Basta un secondo giocatore che
     * entra mentre il primo sta ancora ricevendo il suo stato.
     *
     * <p>Gli stati intermedi non vengono accodati ma <b>sostituiti</b>: ogni
     * messaggio è un'istantanea completa, quindi mentre uno è in volo l'unico
     * che conta è il più recente. Il client, che scarta gli stati con
     * {@code stateSeq} più vecchia della sua, non vede differenza.
     */
    private static final class StateSender {
        private boolean inFlight;
        private String queued;

        void send(Session ws, String json) {
            synchronized (this) {
                if (inFlight) {
                    queued = json;
                    return;
                }
                inFlight = true;
            }
            write(ws, json);
        }

        /**
         * Invio che salta se c'è già altro in uscita. È per il pong, che serve
         * solo come segno di vita: accodarlo significherebbe rischiare di
         * sostituire uno stato non ancora spedito, e non ce n'è bisogno perché
         * quello stato è a sua volta un segno di vita per il client.
         */
        void sendIfIdle(Session ws, String json) {
            synchronized (this) {
                if (inFlight) return;
                inFlight = true;
            }
            write(ws, json);
        }

        private void write(Session ws, String json) {
            // Non bloccante: lo sweeper è single-thread e serve tutte le stanze,
            // quindi un client con la rete impallata non deve mai ritardare il
            // broadcast (né il timeout dei turni) di tutte le altre.
            RemoteEndpoint.Async remote = ws.getAsyncRemote();
            try {
                remote.setSendTimeout(SEND_TIMEOUT_MS);
                remote.sendText(json, result -> {
                    if (!result.isOK()) {
                        abort(ws, result.getException());
                        return;
                    }

                    String next;
                    synchronized (this) {
                        next = queued;
                        queued = null;
                        if (next == null) {
                            inFlight = false;
                            return;
                        }
                    }
                    write(ws, next);
                });
            } catch (RuntimeException ex) {
                // Se l'invio non parte nemmeno, il callback non arriverà mai:
                // senza questo la sessione resterebbe per sempre "in scrittura"
                // e non riceverebbe più uno stato.
                abort(ws, ex);
            }
        }

        /** Invio impossibile: si svuota la coda e si chiude la sessione. */
        private void abort(Session ws, Throwable cause) {
            synchronized (this) {
                inFlight = false;
                queued = null;
            }
            LOG.log(Level.FINE, cause, () -> "Invio stato fallito, chiudo la sessione");
            closeQuietly(ws, "IO error");
        }
    }

    /** Invia il JSON già pronto alla sessione, senza mai bloccare. */
    private static void sendState(Session ws, String json) {
        senderFor(ws).send(ws, json);
    }

    /**
     * Risposta all'heartbeat. Serve al client per accorgersi di un socket
     * morto: senza una risposta, in una stanza ferma non arriva alcun frame e
     * "silenzio" non distinguerebbe una connessione sana da una caduta. Il
     * client la riconosce dal campo {@code pong} e non la tratta come stato.
     */
    private static void sendPong(Session ws) {
        senderFor(ws).sendIfIdle(ws, PONG_JSON);
    }

    /**
     * La coda vive negli userProperties, così muore con la sessione e non
     * serve un registro da ripulire a mano. Il lookup è sincronizzato sulla
     * mappa perché non tutti i container la forniscono concorrente.
     */
    private static StateSender senderFor(Session ws) {
        Map<String, Object> props = ws.getUserProperties();
        synchronized (props) {
            Object existing = props.get(SENDER_KEY);
            if (existing instanceof StateSender sender) return sender;
            StateSender sender = new StateSender();
            props.put(SENDER_KEY, sender);
            return sender;
        }
    }

    /**
     * Chiude la sessione senza far attendere chi lo chiede. Session.close()
     * spedisce il frame di chiusura in modo bloccante (Tomcat usa
     * sendMessageBlock, fino al blocking send timeout: 20 s di default), e i
     * chiamanti sono lo sweeper single-thread della manutenzione e i thread di
     * I/O del container: un client col buffer TCP pieno fermerebbe il timeout
     * dei turni e l'espulsione dei disconnessi di *tutte* le stanze. La
     * chiusura è comunque best-effort, quindi la deleghiamo al closer.
     */
    private static void closeQuietly(Session ws, String reason) {
        if (ws == null) return;
        CloseReason cr = close(reason);
        try {
            CLOSER.execute(() -> {
                try {
                    ws.close(cr);
                } catch (Exception ignore) {}
            });
        } catch (RejectedExecutionException ex) {
            // Closer già fermo (shutdown del contesto): chiudi qui, in fase di
            // arresto non c'è più nessuno sweeper da proteggere.
            try { ws.close(cr); } catch (Exception ignore) {}
        }
    }

    /** Ferma il closer allo shutdown del contesto (chiamato da RoomMaintenance). */
    public static void shutdown() {
        CLOSER.shutdownNow();
    }

    // package-private per i test
    static String buildStateJson(GameState s, int viewerIndex, boolean viewerIsHost) {
        List<Player> players = s.getPlayers();
        boolean viewerEliminated = false;
        boolean viewerSpectating = false;
        if (viewerIndex >= 0 && viewerIndex < players.size()) {
            Player v = players.get(viewerIndex);
            viewerEliminated = (v != null && v.isEliminated());
            viewerSpectating = (v != null && v.isSpectating());
        }

        boolean inPlay = s.getPhase().isInPlay();

        int handViewIndex = (viewerEliminated && inPlay) ? s.getCurrentIndex() : viewerIndex;
        if (handViewIndex < 0 || handViewIndex >= players.size()) handViewIndex = viewerIndex;
        if (handViewIndex < 0 || handViewIndex >= players.size()) handViewIndex = 0;

        Player handOwner = players.get(handViewIndex);

        Card viewPending = null;
        if (inPlay) {
            if (viewerEliminated) viewPending = s.getPendingDraw();
            else if (viewerIndex == s.getCurrentIndex()) viewPending = s.getPendingDraw();
        }

        Player.BestSuitScore best = handOwner.bestSameSuitScore();

        ObjectNode root = MAPPER.createObjectNode();
        root.put("stateSeq", s.getStateSeq());
        root.put("phase", String.valueOf(s.getPhase()));
        root.put("viewerIndex", viewerIndex);
        root.put("currentIndex", s.getCurrentIndex());
        if (s.getWinnerIndex() != null) root.put("winnerIndex", s.getWinnerIndex());
        else root.putNull("winnerIndex");
        root.put("deckSize", s.getDeck() != null ? s.getDeck().size() : 0);
        root.set("discardTop", cardNode(s.getDiscard().peek()));

        root.put("viewerEliminated", viewerEliminated);
        root.put("viewerSpectating", viewerSpectating);
        root.put("viewerIsHost", viewerIsHost);
        root.put("handViewIndex", handViewIndex);

        putTurnTimer(root, s, inPlay);
        putEvents(root, s);
        putRoundResult(root, s);

        ArrayNode viewHand = root.putArray("viewHand");
        for (Card c : handOwner.getHand()) viewHand.add(cardNode(c));

        root.put("viewBestSuitValue", best.getValue());
        if (best.getSuit() != null) root.put("viewBestSuitLabel", best.getSuit().label());
        else root.putNull("viewBestSuitLabel");
        root.set("viewPending", cardNode(viewPending));

        putPlayers(root, s, viewerIndex, viewerEliminated);

        return root.toString();
    }

    /** Timer di turno (secondi rimanenti), solo a partita in corso. */
    private static void putTurnTimer(ObjectNode root, GameState s, boolean inPlay) {
        long deadline = s.getTurnDeadlineMs();
        if (inPlay && deadline > 0) {
            long leftMs = Math.max(0, deadline - System.currentTimeMillis());
            root.put("turnSecondsLeft", (int) ((leftMs + 999) / 1000));
        } else {
            root.putNull("turnSecondsLeft");
        }
    }

    /** Registro mosse: le più recenti per prime. */
    private static void putEvents(ObjectNode root, GameState s) {
        ArrayNode eventsArr = root.putArray("events");
        List<GameState.Event> events = s.getEvents();
        int firstEvent = Math.max(0, events.size() - EVENTS_IN_STATE);
        for (int i = events.size() - 1; i >= firstEvent; i--) {
            GameState.Event e = events.get(i);
            ObjectNode en = eventsArr.addObject();
            en.put("id", e.getId());
            en.put("at", e.getAtMs());
            en.put("msg", e.getMessage());
        }
    }

    /** Esito dell'ultimo round: mani rivelate (il round è concluso, non sono segrete). */
    private static void putRoundResult(ObjectNode root, GameState s) {
        GameState.RoundResult rr = s.getRoundResult();
        if (rr == null) {
            root.putNull("roundResult");
            return;
        }

        ObjectNode rn = root.putObject("roundResult");
        rn.put("id", rr.getId());
        // Età relativa (non timestamp assoluto): il client non deve
        // dipendere dall'allineamento del proprio orologio col server.
        rn.put("ageMs", Math.max(0, System.currentTimeMillis() - rr.getAtMs()));
        rn.put("message", rr.getMessage());
        ArrayNode handsArr = rn.putArray("hands");
        for (GameState.RevealedHand h : rr.getHands()) {
            ObjectNode hn = handsArr.addObject();
            hn.put("index", h.getIndex());
            hn.put("name", h.getName());
            hn.put("score", h.getScore());
            hn.put("lostLife", h.isLostLife());
            hn.put("eliminated", h.isEliminated());
            ArrayNode cardsArr = hn.putArray("cards");
            for (Card c : h.getCards()) cardsArr.add(cardNode(c));
        }
    }

    /** Elenco giocatori; carta pescata e notifica solo per il viewer legittimo. */
    private static void putPlayers(ObjectNode root, GameState s, int viewerIndex, boolean viewerEliminated) {
        List<Player> players = s.getPlayers();
        ArrayNode playersArr = root.putArray("players");
        GameState.Notice viewerNotice = s.getNoticeForPlayer(viewerIndex);
        for (int i = 0; i < players.size(); i++) {
            Player p = players.get(i);

            ObjectNode pn = playersArr.addObject();
            pn.put("index", i);
            pn.put("name", p.getName());
            pn.put("lives", p.getLives());
            pn.put("eliminated", p.isEliminated());
            pn.put("spectating", p.isSpectating());
            pn.put("joined", p.isJoined());
            pn.put("cardCount", p.getHand().size());

            Card pendingForViewer = (!viewerEliminated
                    && i == viewerIndex
                    && viewerIndex == s.getCurrentIndex())
                    ? s.getPendingDraw()
                    : null;
            pn.set("pendingDraw", cardNode(pendingForViewer));

            if (i == viewerIndex && viewerNotice != null) {
                pn.put("noticeId", viewerNotice.getId());
                pn.put("noticeMsg", viewerNotice.getMessage());
            } else {
                pn.putNull("noticeId");
                pn.putNull("noticeMsg");
            }
        }
    }

    private static JsonNode cardNode(Card c) {
        if (c == null) return NullNode.getInstance();
        ObjectNode n = MAPPER.createObjectNode();
        n.put("suit", String.valueOf(c.suit()));
        n.put("rank", String.valueOf(c.rank()));
        n.put("value", c.value());
        n.put("label", c.label());
        return n;
    }

    /**
     * I testi di chiusura sono contratto col client: li confronta per
     * distinguere le chiusure definitive da quelle riconnettibili.
     * Non riformularli senza aggiornare FATAL_CLOSE_REASONS in js/net.js.
     */
    private static CloseReason close(String msg) {
        return new CloseReason(CloseReason.CloseCodes.CANNOT_ACCEPT, msg);
    }

    // --- Ausili per i test: ROOM_SESSIONS è privato, non manipolabile dai test. ---

    static void seedRoomSessionForTest(String roomId, Session ws) {
        ROOM_SESSIONS.computeIfAbsent(RoomRepository.normalizeRoomId(roomId),
                k -> ConcurrentHashMap.newKeySet()).add(ws);
    }

    static boolean hasRoomSessionsEntryForTest(String roomId) {
        return ROOM_SESSIONS.containsKey(RoomRepository.normalizeRoomId(roomId));
    }

    /** Sessioni ancora aperte nella stanza: i test E2E ci attendono sopra. */
    static int openSessionCountForTest(String roomId) {
        Set<Session> set = ROOM_SESSIONS.get(RoomRepository.normalizeRoomId(roomId));
        if (set == null) return 0;
        int n = 0;
        for (Session ws : set) if (ws != null && ws.isOpen()) n++;
        return n;
    }
}
