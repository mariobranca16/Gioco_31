package it.gioco31.room;

import it.gioco31.GameConstants;
import it.gioco31.model.GameState;
import it.gioco31.model.Phase;
import it.gioco31.model.Player;
import it.gioco31.service.ThirtyOneEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class GameRoom {
    private static final Logger LOG = Logger.getLogger(GameRoom.class.getName());

    private final String roomId;
    private final GameState state;
    private final ThirtyOneEngine engine = new ThirtyOneEngine();
    private final ReentrantLock lock = new ReentrantLock();

    private final Map<String, Integer> tokenToIndex = new ConcurrentHashMap<>();

    /** token -> istante (ms) oltre il quale il giocatore disconnesso va rimosso. */
    private final Map<String, Long> disconnectDeadlineMs = new ConcurrentHashMap<>();

    /** Host a cui è già stata concessa la grazia lunga di lobby: non va rinnovata. */
    private final Set<String> hostLobbyGraceGranted = ConcurrentHashMap.newKeySet();

    /** Token del creatore della stanza (o del suo successore se è uscito). */
    private volatile String hostToken = null;

    private volatile long lastActivityMs = System.currentTimeMillis();

    public GameRoom(String roomId, GameState state) {
        this.roomId = roomId;
        this.state = state;
        this.state.setPhase(Phase.WAITING_FOR_PLAYERS);
        touch();
    }

    public String roomId() { return roomId; }
    public GameState state() { return state; }
    public ThirtyOneEngine engine() { return engine; }
    public ReentrantLock lock() { return lock; }

    public long getLastActivityMs() { return lastActivityMs; }

    public void touch() { lastActivityMs = System.currentTimeMillis(); }

    public boolean hasAnyJoinedPlayers() {
        for (Player p : state.getPlayers()) {
            if (p != null && p.isJoined()) return true;
        }
        return false;
    }

    public void bindToken(String token, int index) {
        lock.lock();
        try {
            tokenToIndex.put(token, index);
            // Parte già in grazia (estesa: il primo caricamento pagina può
            // essere lento): se il WebSocket non si apre mai, il posto si libera.
            disconnectDeadlineMs.put(token, System.currentTimeMillis() + GameConstants.FIRST_CONNECT_GRACE_MS);
            if (hostToken == null) hostToken = token;
            touch();
        } finally {
            lock.unlock();
        }
    }

    public Integer indexByToken(String token) {
        return tokenToIndex.get(token);
    }

    /** L'host può avviare la partita e rigiocare. */
    public boolean isHost(String token) {
        return token != null && token.equals(hostToken);
    }

    /** Se l'host non è più nella stanza, promuove il giocatore con indice più basso. */
    private void reassignHostIfNeeded() {
        String current = hostToken;
        if (current != null && tokenToIndex.containsKey(current)) return;

        String best = null;
        int bestIdx = Integer.MAX_VALUE;
        for (var e : tokenToIndex.entrySet()) {
            if (e.getValue() < bestIdx) {
                bestIdx = e.getValue();
                best = e.getKey();
            }
        }
        hostToken = best;
    }

    /** Il giocatore ha (ri)aperto un WebSocket: annulla l'eventuale scadenza. */
    public void markConnected(String token) {
        if (token == null) return;
        lock.lock();
        try {
            if (!tokenToIndex.containsKey(token)) return;
            disconnectDeadlineMs.remove(token);
            // Riconnesso: azzera l'eventuale grazia di lobby già concessa, così
            // una futura disconnessione riparte dalla grazia normale.
            hostLobbyGraceGranted.remove(token);
            touch();
        } finally {
            lock.unlock();
        }
    }

    /** L'ultimo WebSocket del giocatore si è chiuso: avvia il periodo di grazia. */
    public void markDisconnected(String token, long nowMs) {
        if (token == null) return;
        lock.lock();
        try {
            if (!tokenToIndex.containsKey(token)) return;
            disconnectDeadlineMs.put(token, nowMs + GameConstants.DISCONNECT_GRACE_MS);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Rimuove dalla partita i giocatori la cui grazia è scaduta.
     * Ritorna i token rimossi (se non vuoto, lo stato va ritrasmesso ai client).
     */
    public List<String> sweepDisconnected(long nowMs) {
        List<String> removed = new ArrayList<>();
        for (var e : disconnectDeadlineMs.entrySet()) {
            if (nowMs < e.getValue()) continue;
            lock.lock();
            try {
                // Decisione e rimozione sotto lo stesso lock di markConnected:
                // chi si riconnette entro la grazia non può più essere rimosso
                // da uno sweep concorrente.
                Long deadline = disconnectDeadlineMs.get(e.getKey());
                if (deadline == null || nowMs < deadline) continue;

                // Il creatore non perde il posto mentre aspetta gli amici, ma
                // solo per un tempo limitato: senza di lui la partita non può
                // partire, però una stanza abbandonata non deve restare in
                // memoria per sempre. Alla prima scadenza della grazia normale
                // gliela sostituiamo con la grazia lunga di lobby, una sola
                // volta (rinnovarla a ogni giro dello sweeper significherebbe
                // non farla scadere mai). Scaduta anche quella, l'host viene
                // rimosso come chiunque altro e lo slot si libera.
                if (state.getPhase() == Phase.WAITING_FOR_PLAYERS && e.getKey().equals(hostToken)) {
                    if (hostLobbyGraceGranted.add(e.getKey())) {
                        disconnectDeadlineMs.put(e.getKey(), nowMs + GameConstants.HOST_LOBBY_GRACE_MS);
                        continue;
                    }
                    // Grazia lunga già concessa e ora scaduta: procede alla rimozione.
                }

                releaseTokenAndFreeSlot(e.getKey());
                removed.add(e.getKey());
            } finally {
                lock.unlock();
            }
        }
        return removed;
    }

    /**
     * Se il giocatore di turno ha superato il tempo massimo, il suo turno
     * viene giocato d'ufficio (pesca e scarta la pescata) per non bloccare
     * la partita. Ritorna true se lo stato è cambiato.
     */
    public boolean sweepTurnTimeout(long nowMs) {
        long deadline = state.getTurnDeadlineMs();
        if (deadline <= 0 || nowMs < deadline) return false;

        lock.lock();
        try {
            if (!state.getPhase().isInPlay()) return false;

            deadline = state.getTurnDeadlineMs();
            if (deadline <= 0 || nowMs < deadline) return false;

            String name = state.getPlayers().get(state.getCurrentIndex()).getName();
            try {
                engine.forcePlayCurrentTurn(state);
            } catch (RuntimeException ex) {
                // Non deve mai bloccare lo sweeper: riprova al prossimo giro.
                LOG.log(Level.WARNING, ex,
                        () -> "forcePlayCurrentTurn fallito nella room " + roomId + ", riprovo al prossimo giro");
                state.setTurnDeadlineMs(nowMs + GameConstants.TURN_TIMEOUT_MS);
                return false;
            }
            state.addEvent("Tempo scaduto: il turno di " + name + " è stato giocato d'ufficio.");
            touch();
            return true;
        } finally {
            lock.unlock();
        }
    }

    public void releaseTokenAndFreeSlot(String token) {
        lock.lock();
        try {
            disconnectDeadlineMs.remove(token);
            hostLobbyGraceGranted.remove(token);
            Integer idx = tokenToIndex.remove(token);
            if (idx == null) return;
            if (idx < 0 || idx >= state.getPlayers().size()) return;

            Player p = state.getPlayers().get(idx);

            if (state.getPhase().isInPlay() && !p.isSpectating()) {
                engine.handlePlayerRemoved(state, idx);
            } else if (state.getPhase().isInPlay()) {
                // Spettatore: non era nel round, il gioco non va toccato.
                // Resta eliminato così lo slot vuoto non riceve turni.
                if (p.isJoined()) state.addEvent(p.getName() + " ha lasciato il tavolo.");
            } else {
                if (p.isJoined()) state.addEvent(p.getName() + " ha lasciato il tavolo.");
                p.getHand().clear();
                p.setEliminated(false);
            }

            p.setJoined(false);
            p.setSpectating(false);
            p.setName("Slot " + (idx + 1));

            reassignHostIfNeeded();
            touch();

        } finally {
            lock.unlock();
        }
    }
}
