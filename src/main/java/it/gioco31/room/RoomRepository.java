package it.gioco31.room;

import it.gioco31.GameConstants;
import it.gioco31.model.GameState;
import it.gioco31.model.Player;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class RoomRepository {
    private static final ConcurrentMap<String, GameRoom> ROOMS = new ConcurrentHashMap<>();

    private static final SecureRandom RND = new SecureRandom();
    private static final char[] ALPH = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();

    /** Serializza tetto + inserimento in {@link #createNewRoom(int, int, int)}. */
    private static final Object CREATE_LOCK = new Object();

    private RoomRepository() {}

    public static String normalizeRoomId(String roomId) {
        if (roomId == null) return null;
        return roomId.trim().toUpperCase();
    }

    private static boolean putIfAbsent(GameRoom room) {
        return ROOMS.putIfAbsent(normalizeRoomId(room.roomId()), room) == null;
    }

    public static GameRoom get(String roomId) {
        return ROOMS.get(normalizeRoomId(roomId));
    }

    /**
     * Toglie una stanza dal registro. Il ciclo di vita normale passa da
     * {@link #cleanupStaleRooms}: serve ai test per non lasciare stanze nella
     * mappa statica, che è condivisa da tutte le classi della stessa JVM.
     */
    public static void remove(String roomId) {
        ROOMS.remove(normalizeRoomId(roomId));
    }

    /** Vista di sola lettura delle stanze attive (usata dallo sweeper periodico). */
    public static Collection<GameRoom> allRooms() {
        return Collections.unmodifiableCollection(ROOMS.values());
    }

    /**
     * Pulizia delle stanze stantie. Unico punto di innesco: lo sweeper
     * periodico di {@link RoomMaintenance}, che la invoca a ogni giro.
     * Ritorna gli id delle stanze rimosse, così il chiamante può ripulire
     * anche eventuali risorse collegate (es. il registro delle sessioni WS).
     */
    public static List<String> cleanupStaleRoomsNow() {
        return cleanupStaleRooms(System.currentTimeMillis());
    }

    public static boolean exists(String roomId) {
        return ROOMS.containsKey(normalizeRoomId(roomId));
    }

    public static GameRoom createNewRoom(int slots, int lives) {
        if (slots < GameConstants.MIN_PLAYERS || slots > GameConstants.MAX_PLAYERS)
            throw new IllegalArgumentException("slots deve essere tra " + GameConstants.MIN_PLAYERS + " e " + GameConstants.MAX_PLAYERS);
        if (lives < GameConstants.MIN_LIVES || lives > GameConstants.MAX_LIVES)
            throw new IllegalArgumentException("lives deve essere tra " + GameConstants.MIN_LIVES + " e " + GameConstants.MAX_LIVES);

        return createNewRoom(GameConstants.DEFAULT_ROOM_ID_LEN, slots, lives);
    }

    private static GameRoom createNewRoom(int roomIdLen, int slots, int lives) {
        // La creazione è serializzata perché il tetto va verificato insieme
        // all'inserimento: con un semplice size() fuori dal lock, N richieste
        // simultanee leggerebbero tutte lo stesso valore e sforerebbero. È una
        // POST rara e il corpo è breve, quindi il costo non si nota. Le rimozioni
        // concorrenti non danno fastidio: possono solo far scendere size().
        synchronized (CREATE_LOCK) {
            if (ROOMS.size() >= GameConstants.MAX_ROOMS) {
                throw new RoomLimitReachedException(
                        "Raggiunto il numero massimo di stanze attive (" + GameConstants.MAX_ROOMS + ").");
            }

            for (int attempt = 0; attempt < 10_000; attempt++) {
                String roomId = randomRoomId(roomIdLen);

                List<Player> players = new ArrayList<>(slots);
                for (int i = 0; i < slots; i++) {
                    players.add(new Player("Slot " + (i + 1), lives));
                }

                GameState state = new GameState(players, lives);

                GameRoom room = new GameRoom(roomId, state);
                if (putIfAbsent(room)) return room;
            }
            throw new IllegalStateException("Impossibile creare una room (collisioni roomId).");
        }
    }

    /** Il registro è pieno: la richiesta è legittima, ma non ora. */
    public static final class RoomLimitReachedException extends IllegalStateException {
        public RoomLimitReachedException(String message) { super(message); }
    }

    private static String randomRoomId(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(ALPH[RND.nextInt(ALPH.length)]);
        return sb.toString();
    }

    // package-private: i test iniettano il tempo corrente per non dover attendere.
    static List<String> cleanupStaleRooms(long now) {
        List<String> removed = new ArrayList<>();
        for (var e : ROOMS.entrySet()) {
            GameRoom room = e.getValue();
            if (room == null) continue;

            boolean stale = (now - room.getLastActivityMs()) > GameConstants.ROOM_STALE_MS;
            if (stale && !room.hasAnyJoinedPlayers()) {
                if (ROOMS.remove(e.getKey(), room)) removed.add(e.getKey());
            }
        }
        return removed;
    }
}
