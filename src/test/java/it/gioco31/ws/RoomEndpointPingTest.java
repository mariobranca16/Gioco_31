package it.gioco31.ws;

import it.gioco31.room.GameRoom;
import jakarta.websocket.Session;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

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
        endpoint.onMessage(ws, action("ping"), rid);
        assertEquals(before, room.state().getStateSeq(),
                "il ping non deve incrementare stateSeq né provocare un broadcast");

        // Un'azione non-ping che modifica lo stato ritrasmette: stateSeq cresce.
        endpoint.onMessage(ws, armedAckNotice(room, 0), rid);
        assertTrue(room.state().getStateSeq() > before,
                "un'azione non-ping produce un broadcast e incrementa stateSeq");
    }

    /**
     * Senza risposta il client non avrebbe modo di distinguere una connessione
     * sana da una morta: in una stanza ferma non arriva alcun frame. Il pong è
     * il segno di vita su cui si regge il controllo di liveness in js/net.js.
     */
    @Test
    void pingIsAnsweredWithAPong() {
        GameRoom room = newRoom(2);
        String rid = room.roomId();
        room.bindToken("t0", 0);

        List<Sent> sent = new ArrayList<>();
        Session ws = recordingSession("t0", sent);
        RoomEndpoint.seedRoomSessionForTest(rid, ws);

        new RoomEndpoint().onMessage(ws, action("ping"), rid);

        assertEquals(1, sent.size(), "il ping riceve una risposta");
        assertEquals("{\"pong\":true}", sent.get(0).json());
        assertEquals(0, room.state().getStateSeq(),
                "la risposta è per il solo mittente: nessun broadcast, nessun bump di stateSeq");
    }

    @Test
    void thePongIsSkippedWhileSomethingElseIsAlreadyGoingOut() {
        GameRoom room = newRoom(2);
        String rid = room.roomId();
        room.bindToken("t0", 0);

        List<Sent> sent = new ArrayList<>();
        Session ws = recordingSession("t0", sent);
        RoomEndpoint.seedRoomSessionForTest(rid, ws);

        RoomEndpoint.broadcastRoom(rid); // stato in volo, non ancora concluso
        assertEquals(1, sent.size());

        new RoomEndpoint().onMessage(ws, action("ping"), rid);

        // Accodare il pong rischierebbe di sostituire uno stato non ancora
        // spedito, e non serve: quello stato è già un segno di vita.
        assertEquals(1, sent.size(), "con altro traffico in uscita il pong si salta");
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
            Thread pinger = new Thread(() -> endpoint.onMessage(ws, action("ping"), rid));
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
