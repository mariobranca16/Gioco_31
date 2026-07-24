package it.gioco31.ws;

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
import java.util.logging.Level;
import java.util.logging.Logger;

@ServerEndpoint(value = "/ws/{roomId}", configurator = HttpSessionConfigurator.class)
public class RoomEndpoint {

    private static final Logger LOG = Logger.getLogger(RoomEndpoint.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Attesa massima per l'invio a un singolo client prima di considerarlo bloccato. */
    private static final long SEND_TIMEOUT_MS = 5_000L;

    /** Quante voci del registro mosse viaggiano in ogni stato. */
    private static final int EVENTS_IN_STATE = 15;

    /** Anti-abuso del canale: lunghezza massima e messaggi al secondo per sessione. */
    private static final int MAX_MSG_LEN = 200;
    private static final int MAX_MSG_PER_SEC = 20;
    private static final String RATE_KEY = "rate";

    /**
     * Finestra scorrevole O(1): conserva il timestamp degli ultimi
     * MAX_MSG_PER_SEC messaggi ammessi. Se il più vecchio dei venti è entro
     * l'ultimo secondo, la soglia è superata. Un'istanza per sessione, negli
     * userProperties; il container consegna i messaggi di una sessione in
     * serie, quindi non serve sincronizzazione.
     */
    private static final class RateWindow {
        private final long[] ring = new long[MAX_MSG_PER_SEC];
        private int idx = 0;

        boolean allow(long nowMs) {
            // ring[idx] è il 20°-ultimo messaggio ammesso (0 = mai, quindi lontano).
            if (nowMs - ring[idx] < 1000L) return false;
            ring[idx] = nowMs;
            idx = (idx + 1) % ring.length;
            return true;
        }
    }

    private static final Map<String, Set<Session>> ROOM_SESSIONS = new ConcurrentHashMap<>();

    /**
     * HttpSession del client, iniettata dal configurator per questa singola
     * connessione. Non passa più dalla mappa condivisa di ServerEndpointConfig,
     * che due handshake simultanei potrebbero sovrascriversi a vicenda.
     */
    private final HttpSession http;

    public RoomEndpoint(HttpSession http) {
        this.http = http;
    }

    @OnOpen
    public void onOpen(Session ws, @PathParam("roomId") String roomId) throws IOException {
        String rid = RoomRepository.normalizeRoomId(roomId);

        GameRoom room = RoomRepository.get(rid);
        if (room == null) { ws.close(close("Room not found")); return; }

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
        if (!allowRate(ws)) return;

        String rid = RoomRepository.normalizeRoomId(roomId);

        GameRoom room = RoomRepository.get(rid);
        if (room == null) return;

        String token = (String) ws.getUserProperties().get("token");
        if (token == null) return;

        Integer me = room.indexByToken(token);
        if (me == null) {
            closeQuietly(ws, "Invalid player");
            return;
        }

        String[] parts = msg.split(":", 3);
        if (parts.length < 2 || !"ACTION".equals(parts[0])) return;

        boolean isPing = "ping".equals(parts[1]);

        room.lock().lock();
        try {
            applyAction(room, me, room.isHost(token), parts);
            room.touch();
        } finally {
            room.lock().unlock();
        }

        // Il ping è solo heartbeat: non cambia lo stato, quindi niente broadcast
        // (né bumpSeq). 6 client con un ping ogni 20 s genererebbero altrimenti
        // traffico e incrementi di sequenza inutili in continuazione.
        if (!isPing) broadcast(rid, room);
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
            room.markDisconnected(token, System.currentTimeMillis());
        }
    }

    private static boolean allowRate(Session ws) {
        Map<String, Object> props = ws.getUserProperties();
        RateWindow rw = (RateWindow) props.get(RATE_KEY);
        if (rw == null) {
            rw = new RateWindow();
            props.put(RATE_KEY, rw);
        }
        return rw.allow(System.currentTimeMillis());
    }

    private static boolean hasOpenSessionForToken(String rid, String token) {
        Set<Session> set = ROOM_SESSIONS.get(rid);
        if (set == null) return false;
        for (Session s : set) {
            if (s.isOpen() && token.equals(s.getUserProperties().get("token"))) return true;
        }
        return false;
    }

    private void applyAction(GameRoom room, int me, boolean isHost, String[] parts) {
        GameState s = room.state();

        String action = parts[1];

        if ("ping".equals(action)) {
            // Heartbeat del client: tiene viva la connessione (i proxy chiudono
            // i WebSocket inattivi) e segnala attività. Non modifica lo stato;
            // il broadcast è soppresso in onMessage.
            room.touch();
            return;
        }

        if ("ackNotice".equals(action)) {
            if (parts.length >= 3) {
                try {
                    long id = Long.parseLong(parts[2]);
                    s.clearNoticeForPlayer(me, id);
                } catch (NumberFormatException ignored) {}
            }
            return;
        }

        if ("restartGame".equals(action)) {
            if (s.getPhase() != Phase.GAME_OVER) return;
            if (!isHost) return;
            restartGame(room, s);
            return;
        }

        if (me < 0 || me >= s.getPlayers().size()) return;
        if (s.getPlayers().get(me).isEliminated()) return;

        if (s.getPhase() == Phase.WAITING_FOR_PLAYERS) return;
        if (s.getPhase() == Phase.GAME_OVER) return;
        if (s.getCurrentIndex() != me) return;

        switch (action) {
            case "drawDeck"    -> applyOrNotify(s, me, () -> room.engine().drawPendingFromDeck(s));
            case "drawDiscard" -> applyOrNotify(s, me, () -> room.engine().drawPendingFromDiscard(s));
            case "keep" -> {
                Integer idx = parseIntOrNull(parts.length >= 3 ? parts[2] : null);
                if (idx != null) applyOrNotify(s, me, () -> room.engine().keepPendingDraw(s, idx));
            }
            case "reject" -> applyOrNotify(s, me, () -> room.engine().rejectDraw(s));
            case "knock"   -> applyOrNotify(s, me, () -> room.engine().knock(s));
            default -> { /* ignore */ }
        }
    }

    /** Esegue una mossa; un rifiuto del motore diventa una notifica per il giocatore. */
    private static void applyOrNotify(GameState s, int me, Runnable move) {
        try {
            move.run();
        } catch (RuntimeException ex) {
            s.setNoticeForPlayer(me, ex.getMessage());
        }
    }

    private static Integer parseIntOrNull(String raw) {
        if (raw == null) return null;
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            return null;
        }
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

    /** Invia il JSON già pronto alla sessione, senza mai bloccare. */
    private static void sendState(Session ws, String json) {
        // Invio asincrono e non bloccante: lo sweeper è single-thread e serve
        // tutte le stanze, quindi un client con la rete impallata non deve mai
        // ritardare il broadcast (né il timeout dei turni). L'async remote di
        // Tomcat accoda già gli invii della stessa sessione, e senza attesa non
        // c'è nulla da serializzare: niente synchronized. La chiusura in caso
        // di errore avviene nel callback.
        RemoteEndpoint.Async remote = ws.getAsyncRemote();
        remote.setSendTimeout(SEND_TIMEOUT_MS);
        remote.sendText(json, result -> {
            if (!result.isOK()) {
                LOG.log(Level.FINE, result.getException(),
                        () -> "Invio asincrono stato fallito, chiudo la sessione");
                closeQuietly(ws, "IO error");
            }
        });
    }

    private static void closeQuietly(Session ws, String reason) {
        if (ws == null) return;
        try {
            ws.close(close(reason));
        } catch (Exception ignore) {}
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
     * I testi di chiusura sono contratto col client: room.js li confronta
     * per distinguere le chiusure definitive da quelle riconnettibili.
     * Non riformularli senza aggiornare la lista in room.js.
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
}
