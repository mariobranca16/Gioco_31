package it.gioco31.room;

import it.gioco31.GameConstants;
import it.gioco31.model.Phase;
import org.junit.jupiter.api.Test;

import java.util.List;

import static it.gioco31.testutil.Fixtures.newRoom;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Gestione delle disconnessioni: chi chiude la scheda senza premere "Esci"
 * ha un periodo di grazia per riconnettersi, dopodiché viene rimosso
 * dalla partita come se avesse fatto leave.
 */
class GameRoomDisconnectTest {

    private static final long GRACE = GameConstants.DISCONNECT_GRACE_MS;
    private static final long FIRST_GRACE = GameConstants.FIRST_CONNECT_GRACE_MS;

    @Test
    void tokenThatNeverConnectsExpiresAfterFirstConnectGrace() {
        GameRoom room = newRoom(2);
        long now = System.currentTimeMillis();

        assertTrue(room.sweepDisconnected(now + GRACE + 1).isEmpty(),
                "prima del primo collegamento vale la grazia estesa, non quella standard");

        List<String> expired = room.sweepDisconnected(now + FIRST_GRACE + 1);

        assertEquals(List.of("t1"), expired,
                "il non-host che non apre mai il WebSocket viene rimosso");
        assertNull(room.indexByToken("t1"));
        assertFalse(room.state().getPlayers().get(1).isJoined());
    }

    @Test
    void hostIsNotEvictedWhileWaitingForPlayers() {
        GameRoom room = newRoom(2);
        long now = System.currentTimeMillis();

        room.sweepDisconnected(now + FIRST_GRACE * 10);

        assertEquals(0, room.indexByToken("t0"),
                "in attesa di giocatori il creatore non perde mai il posto");
        assertTrue(room.isHost("t0"));
        assertTrue(room.state().getPlayers().get(0).isJoined());
    }

    @Test
    void hostInLobbyHandsTheRoleToWhoIsStillConnected() {
        GameRoom room = newRoom(2);
        room.markConnected("t0");
        room.markConnected("t1");
        long now = System.currentTimeMillis();
        room.markDisconnected("t0", now);

        List<String> expired = room.sweepDisconnected(now + GRACE + 1);

        assertEquals(List.of("t0"), expired,
                "con qualcun altro connesso l'host esce come chiunque altro");
        assertTrue(room.isHost("t1"),
                "il ruolo passa subito a chi è rimasto: la lobby deve restare avviabile");
        assertNull(room.indexByToken("t0"));
    }

    @Test
    void hostAloneInLobbyIsEvictedOnlyAfterLobbyGrace() {
        GameRoom room = newRoom(2);
        room.markConnected("t0");
        room.releaseTokenAndFreeSlot("t1"); // l'host resta solo: nessun successore
        long now = System.currentTimeMillis();
        room.markDisconnected("t0", now);

        // La sola grazia normale non basta a rimuovere l'host solo in lobby.
        assertTrue(room.sweepDisconnected(now + GRACE + 1).isEmpty(),
                "l'host solo in lobby non scade con la sola grazia normale");
        assertEquals(0, room.indexByToken("t0"));
        assertTrue(room.state().getPlayers().get(0).isJoined());

        // Scaduta anche la grazia lunga di lobby, l'host viene rimosso come gli altri.
        long afterLobby = now + GRACE + GameConstants.HOST_LOBBY_GRACE_MS + 100;
        List<String> expired = room.sweepDisconnected(afterLobby);

        assertEquals(List.of("t0"), expired,
                "dopo la grazia lunga di lobby il posto dell'host si libera");
        assertNull(room.indexByToken("t0"));
        assertFalse(room.state().getPlayers().get(0).isJoined());
    }

    @Test
    void theLobbyGraceIsSpentOncePerRoomNotOncePerHost() {
        GameRoom room = newRoom(2);
        room.markConnected("t0");
        room.releaseTokenAndFreeSlot("t1");
        long now = System.currentTimeMillis();
        room.markDisconnected("t0", now);

        room.sweepDisconnected(now + GRACE + 1);              // concede la grazia lunga
        long afterLobby = now + GRACE + GameConstants.HOST_LOBBY_GRACE_MS + 100;
        room.sweepDisconnected(afterLobby);                    // e la esaurisce
        assertNull(room.indexByToken("t0"));

        // Arriva un visitatore che eredita il ruolo di host e non apre mai il
        // WebSocket: se la grazia lunga fosse per token, si prenderebbe un'altra
        // mezz'ora, e ogni visitatore terrebbe in vita la stanza all'infinito.
        room.bindToken("t2", 0);
        assertTrue(room.isHost("t2"), "senza host, chi arriva eredita il ruolo");

        List<String> after = room.sweepDisconnected(afterLobby + 1);

        assertEquals(List.of("t2"), after,
                "la grazia lunga appartiene alla stanza: il nuovo host non ne ottiene un'altra");
        assertFalse(room.hasAnyJoinedPlayers());
    }

    @Test
    void connectedTokenNeverExpires() {
        GameRoom room = newRoom(2);
        room.markConnected("t0");
        room.markConnected("t1");

        long farFuture = System.currentTimeMillis() + GRACE * 10;

        assertTrue(room.sweepDisconnected(farFuture).isEmpty());
        assertTrue(room.state().getPlayers().get(0).isJoined());
        assertTrue(room.state().getPlayers().get(1).isJoined());
    }

    @Test
    void disconnectedPlayerIsRemovedFromRunningGameAfterGrace() {
        GameRoom room = newRoom(2);
        room.markConnected("t0");
        room.markConnected("t1");
        room.engine().startRound(room.state());

        long now = System.currentTimeMillis();
        room.markDisconnected("t0", now);

        assertTrue(room.sweepDisconnected(now + GRACE - 1).isEmpty(),
                "prima della scadenza il giocatore resta in partita");
        assertTrue(room.state().getPlayers().get(0).isJoined());

        List<String> expired = room.sweepDisconnected(now + GRACE + 1);

        assertEquals(List.of("t0"), expired);
        // riusa la logica di leave: resta un solo attivo, vince lui
        assertEquals(Phase.GAME_OVER, room.state().getPhase());
        assertEquals(1, room.state().getWinnerIndex());
        assertNull(room.indexByToken("t0"));
    }

    @Test
    void reconnectionWithinGraceCancelsExpiry() {
        GameRoom room = newRoom(2);
        room.markConnected("t0");
        room.markConnected("t1");

        long now = System.currentTimeMillis();
        room.markDisconnected("t0", now);
        room.markConnected("t0"); // riconnesso in tempo

        assertTrue(room.sweepDisconnected(now + GRACE * 10).isEmpty());
        assertTrue(room.state().getPlayers().get(0).isJoined());
        assertEquals(0, room.indexByToken("t0"));
    }

    @Test
    void markDisconnectedIgnoresUnknownTokens() {
        GameRoom room = newRoom(2);
        room.markConnected("t0");
        room.markConnected("t1");

        long now = System.currentTimeMillis();
        room.markDisconnected("ghost-token", now);

        assertTrue(room.sweepDisconnected(now + GRACE * 10).isEmpty());
    }
}
