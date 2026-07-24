package it.gioco31.room;

import it.gioco31.model.GameState;
import it.gioco31.model.Player;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L'host è legato al token del creatore, non allo slot 0:
 * se l'host esce, viene promosso il giocatore rimasto con indice più basso,
 * non chiunque occupi lo slot liberato in seguito.
 */
class GameRoomHostTest {

    private static GameRoom newRoom(int nSlots) {
        List<Player> players = new ArrayList<>();
        for (int i = 0; i < nSlots; i++) players.add(new Player("Slot " + (i + 1), 3));
        return new GameRoom("TEST", new GameState(players, 42L, 3));
    }

    private static void join(GameRoom room, String token, int idx) {
        Player p = room.state().getPlayers().get(idx);
        p.setName("Player" + (idx + 1));
        p.setJoined(true);
        room.bindToken(token, idx);
    }

    @Test
    void firstBoundTokenIsHost() {
        GameRoom room = newRoom(3);
        join(room, "creator", 0);
        join(room, "second", 1);

        assertTrue(room.isHost("creator"));
        assertFalse(room.isHost("second"));
        assertFalse(room.isHost(null));
    }

    @Test
    void hostLeavePromotesLowestIndexPlayer() {
        GameRoom room = newRoom(3);
        join(room, "creator", 0);
        join(room, "second", 1);
        join(room, "third", 2);

        room.releaseTokenAndFreeSlot("creator");

        assertFalse(room.isHost("creator"));
        assertTrue(room.isHost("second"));
        assertFalse(room.isHost("third"));
    }

    @Test
    void newJoinerInFreedSlotZeroDoesNotBecomeHost() {
        GameRoom room = newRoom(3);
        join(room, "creator", 0);
        join(room, "second", 1);

        room.releaseTokenAndFreeSlot("creator"); // host -> "second"
        join(room, "latecomer", 0);              // occupa lo slot 0 liberato

        assertTrue(room.isHost("second"));
        assertFalse(room.isHost("latecomer"));
    }

    @Test
    void nonHostLeaveKeepsHost() {
        GameRoom room = newRoom(3);
        join(room, "creator", 0);
        join(room, "second", 1);

        room.releaseTokenAndFreeSlot("second");

        assertTrue(room.isHost("creator"));
    }

    @Test
    void whenEveryoneLeavesNextJoinerBecomesHost() {
        GameRoom room = newRoom(2);
        join(room, "creator", 0);
        room.releaseTokenAndFreeSlot("creator");

        join(room, "fresh", 0);

        assertTrue(room.isHost("fresh"));
    }
}
