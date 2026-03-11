package it.gioco31.room;

import it.gioco31.GameConstants;
import it.gioco31.model.GameState;
import it.gioco31.model.Player;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

public final class RoomRepository {
    private static final ConcurrentMap<String, GameRoom> ROOMS = new ConcurrentHashMap<>();
    private static final long CLEANUP_INTERVAL_MS = 60_000L;
    private static final AtomicLong NEXT_CLEANUP_AT_MS = new AtomicLong(0L);

    private static final SecureRandom RND = new SecureRandom();
    private static final char[] ALPH = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();

    private RoomRepository() {}

    private static String norm(String roomId) {
        if (roomId == null) return null;
        return roomId.trim().toUpperCase();
    }

    public static String normalizeRoomId(String roomId) {
        return norm(roomId);
    }

    public static boolean putIfAbsent(GameRoom room) {
        cleanupStaleRoomsIfNeeded();
        return ROOMS.putIfAbsent(norm(room.roomId()), room) == null;
    }

    public static GameRoom get(String roomId) {
        cleanupStaleRoomsIfNeeded();
        return ROOMS.get(norm(roomId));
    }

    public static boolean exists(String roomId) {
        cleanupStaleRoomsIfNeeded();
        return ROOMS.containsKey(norm(roomId));
    }

    public static GameRoom createNewRoom(int slots) {
        cleanupStaleRoomsIfNeeded();

        if (slots < GameConstants.MIN_PLAYERS || slots > GameConstants.MAX_PLAYERS)
            throw new IllegalArgumentException("slots deve essere tra " + GameConstants.MIN_PLAYERS + " e " + GameConstants.MAX_PLAYERS);

        return createNewRoom(GameConstants.DEFAULT_ROOM_ID_LEN, slots, GameConstants.DEFAULT_LIVES);
    }

    private static GameRoom createNewRoom(int roomIdLen, int slots, int lives) {
        for (int attempt = 0; attempt < 10_000; attempt++) {
            String roomId = randomRoomId(roomIdLen);

            List<Player> players = new ArrayList<>(slots);
            for (int i = 0; i < slots; i++) {
                players.add(new Player("Slot " + (i + 1), lives));
            }

            long seed = RND.nextLong();
            GameState state = new GameState(players, seed, lives);

            GameRoom room = new GameRoom(roomId, state);
            if (putIfAbsent(room)) return room;
        }
        throw new IllegalStateException("Impossibile creare una room (collisioni roomId).");
    }

    private static String randomRoomId(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(ALPH[RND.nextInt(ALPH.length)]);
        return sb.toString();
    }

    private static void cleanupStaleRoomsIfNeeded() {
        long now = System.currentTimeMillis();
        long nextCleanupAt = NEXT_CLEANUP_AT_MS.get();
        if (now < nextCleanupAt) return;
        if (!NEXT_CLEANUP_AT_MS.compareAndSet(nextCleanupAt, now + CLEANUP_INTERVAL_MS)) return;
        cleanupStaleRooms(now);
    }

    private static void cleanupStaleRooms(long now) {
        for (var e : ROOMS.entrySet()) {
            GameRoom room = e.getValue();
            if (room == null) continue;

            boolean stale = (now - room.getLastActivityMs()) > GameConstants.ROOM_STALE_MS;
            if (stale && !room.hasAnyJoinedPlayers()) {
                ROOMS.remove(e.getKey(), room);
            }
        }
    }
}
