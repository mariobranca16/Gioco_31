package it.gioco31.room;

import it.gioco31.GameConstants;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ciclo di vita delle stanze nel repository: una stanza creata e poi
 * abbandonata dall'host non deve restare in memoria per sempre.
 */
class RoomRepositoryTest {

    @Test
    void roomAbandonedByHostIsEventuallyCleanedUp() {
        GameRoom room = RoomRepository.createNewRoom(2, 3);
        String rid = room.roomId();

        // L'host si è unito (unico slot occupato) e poi ha chiuso il browser.
        room.bindToken("host", 0);
        room.state().getPlayers().get(0).setJoined(true);

        long now = System.currentTimeMillis();

        // Prima scadenza della grazia normale: all'host viene concessa quella lunga.
        room.sweepDisconnected(now + GameConstants.FIRST_CONNECT_GRACE_MS + 1);
        assertNotNull(RoomRepository.get(rid), "l'host in lobby non scade subito");
        assertTrue(room.hasAnyJoinedPlayers());

        // Scaduta la grazia lunga di lobby, l'host è rimosso: nessuno resta seduto.
        room.sweepDisconnected(now + GameConstants.FIRST_CONNECT_GRACE_MS
                + GameConstants.HOST_LOBBY_GRACE_MS + 100);
        assertFalse(room.hasAnyJoinedPlayers(), "liberato lo slot dell'host");

        // Ora la stanza è stantia e senza giocatori: la pulizia la rimuove.
        List<String> removed = RoomRepository.cleanupStaleRooms(
                now + GameConstants.FIRST_CONNECT_GRACE_MS
                        + GameConstants.HOST_LOBBY_GRACE_MS
                        + GameConstants.ROOM_STALE_MS + 200);

        assertTrue(removed.contains(rid), "la stanza abbandonata è tra quelle rimosse");
        assertNull(RoomRepository.get(rid), "la stanza abbandonata non è più presente");
    }
}
