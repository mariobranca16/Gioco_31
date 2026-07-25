package it.gioco31.ws;

import it.gioco31.room.GameRoom;
import jakarta.websocket.Session;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Hardening del canale: i messaggi troppo lunghi e quelli oltre la frequenza
 * consentita vengono scartati in silenzio, senza chiudere la sessione né
 * mutare lo stato (nessun broadcast, quindi nessun incremento di stateSeq).
 * La semantica esatta della finestra è verificata, senza dipendere
 * dall'orologio, in {@link RateWindowTest}.
 */
class RoomEndpointRateLimitTest extends EndpointTestBase {

    /** Una raffica ha senso solo se sta dentro la finestra di un secondo. */
    private static void assumeBurstFitsInTheWindow(long startNanos) {
        assumeTrue(System.nanoTime() - startNanos < 1_000_000_000L,
                "raffica troppo lenta: la finestra di 1 s si era già svuotata");
    }

    @Test
    void messagesOverMaxLengthAreDropped() {
        GameRoom room = newRoom(2);
        String rid = room.roomId();
        room.bindToken("t0", 0);

        Session ws = sessionWithToken("t0");
        RoomEndpoint.seedRoomSessionForTest(rid, ws);
        RoomEndpoint endpoint = new RoomEndpoint();

        long before = room.state().getStateSeq();

        // > 200 caratteri: scartato prima di qualsiasi elaborazione, nessun broadcast.
        String tooLong = "ACTION:ackNotice:" + "1".repeat(200);
        assertTrue(tooLong.length() > 200);
        endpoint.onMessage(ws, tooLong, rid);
        assertEquals(before, room.state().getStateSeq(),
                "un messaggio oltre i 200 caratteri non deve produrre broadcast");

        // Un messaggio normale, invece, passa e ritrasmette.
        endpoint.onMessage(ws, "ACTION:ackNotice:1", rid);
        assertTrue(room.state().getStateSeq() > before,
                "un messaggio di lunghezza valida deve essere elaborato");
    }

    @Test
    void exceedingRatePerSecondIsSilentlyDropped() {
        GameRoom room = newRoom(2);
        String rid = room.roomId();
        room.bindToken("t0", 0);

        Session ws = sessionWithToken("t0");
        RoomEndpoint.seedRoomSessionForTest(rid, ws);
        RoomEndpoint endpoint = new RoomEndpoint();

        long before = room.state().getStateSeq();

        // 20 messaggi validi nello stesso secondo passano; il 21° è scartato.
        long start = System.nanoTime();
        for (int i = 0; i < 21; i++) {
            endpoint.onMessage(ws, "ACTION:ackNotice:1", rid);
        }
        assumeBurstFitsInTheWindow(start);

        assertEquals(before + 20, room.state().getStateSeq(),
                "al massimo 20 messaggi al secondo vengono elaborati, il 21° è scartato");
        assertTrue(ws.isOpen(),
                "oltre soglia il messaggio è ignorato, la sessione non viene chiusa");
    }

    @Test
    void theLimitIsPerPlayerNotPerSocket() {
        GameRoom room = newRoom(2);
        String rid = room.roomId();
        room.bindToken("t0", 0);

        // Due socket dello stesso giocatore: la soglia resta una sola. Se fosse
        // per sessione, basterebbe aprire più WebSocket per moltiplicarla.
        Session first = sessionWithToken("t0");
        Session second = sessionWithToken("t0");
        RoomEndpoint.seedRoomSessionForTest(rid, first);
        RoomEndpoint.seedRoomSessionForTest(rid, second);
        RoomEndpoint endpoint = new RoomEndpoint();

        long before = room.state().getStateSeq();

        long start = System.nanoTime();
        for (int i = 0; i < 11; i++) endpoint.onMessage(first, "ACTION:ackNotice:1", rid);
        for (int i = 0; i < 10; i++) endpoint.onMessage(second, "ACTION:ackNotice:1", rid);
        assumeBurstFitsInTheWindow(start);

        assertEquals(before + 20, room.state().getStateSeq(),
                "21 messaggi su due socket dello stesso token: il 21° va comunque scartato");
    }
}
