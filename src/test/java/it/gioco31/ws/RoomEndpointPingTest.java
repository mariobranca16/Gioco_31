package it.gioco31.ws;

import it.gioco31.room.GameRoom;
import jakarta.websocket.Session;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Il ping è solo heartbeat: non deve toccare lo stato né generare un broadcast
 * (che incrementerebbe stateSeq). Un'azione qualsiasi, invece, ritrasmette.
 */
class RoomEndpointPingTest extends EndpointTestBase {

    @Test
    void pingDoesNotBumpStateSeqNorBroadcast() {
        GameRoom room = newRoom(2);
        String rid = room.roomId();
        room.bindToken("t0", 0);

        Session ws = sessionWithToken("t0");
        RoomEndpoint.seedRoomSessionForTest(rid, ws);

        RoomEndpoint endpoint = new RoomEndpoint();
        long before = room.state().getStateSeq();

        // Il ping non deve ritrasmettere: stateSeq invariato.
        endpoint.onMessage(ws, "ACTION:ping", rid);
        assertEquals(before, room.state().getStateSeq(),
                "il ping non deve incrementare stateSeq né provocare un broadcast");

        // Un'azione non-ping (anche innocua) ritrasmette: stateSeq cresce.
        endpoint.onMessage(ws, "ACTION:ackNotice:1", rid);
        assertTrue(room.state().getStateSeq() > before,
                "un'azione non-ping produce un broadcast e incrementa stateSeq");
    }

    @Test
    void pingKeepsTheRoomAliveWithoutTakingItsLock() throws Exception {
        GameRoom room = newRoom(2);
        String rid = room.roomId();
        room.bindToken("t0", 0);

        Session ws = sessionWithToken("t0");
        RoomEndpoint.seedRoomSessionForTest(rid, ws);
        RoomEndpoint endpoint = new RoomEndpoint();

        long beforeActivity = room.getLastActivityMs();
        Thread.sleep(2); // l'attività è in millisecondi: serve un istante diverso

        // Lock preso da un altro thread, come durante una partita o un
        // broadcast: l'heartbeat non deve restare in attesa.
        room.lock().lock();
        try {
            Thread pinger = new Thread(() -> endpoint.onMessage(ws, "ACTION:ping", rid));
            pinger.start();
            pinger.join(2_000);
            assertFalse(pinger.isAlive(),
                    "il ping non deve mai bloccarsi sul lock della stanza");
        } finally {
            room.lock().unlock();
        }

        assertTrue(room.getLastActivityMs() > beforeActivity,
                "il ping segnala comunque attività, così la stanza non risulta stantia");
    }
}
