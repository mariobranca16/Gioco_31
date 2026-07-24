package it.gioco31.ws;

import it.gioco31.room.GameRoom;
import it.gioco31.room.RoomRepository;
import jakarta.websocket.Session;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Hardening del canale: i messaggi troppo lunghi e quelli oltre la frequenza
 * consentita vengono scartati in silenzio, senza chiudere la sessione né
 * mutare lo stato (nessun broadcast, quindi nessun incremento di stateSeq).
 */
class RoomEndpointRateLimitTest {

    /** Sessione fittizia via proxy: porta il token, getAsyncRemote resta null. */
    private static Session sessionWithToken(String token) {
        Map<String, Object> props = new HashMap<>();
        props.put("token", token);
        return (Session) Proxy.newProxyInstance(
                RoomEndpointRateLimitTest.class.getClassLoader(),
                new Class<?>[]{Session.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUserProperties" -> props;
                    case "isOpen"   -> true;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals"   -> proxy == args[0];
                    default -> null;
                });
    }

    @Test
    void messagesOverMaxLengthAreDropped() {
        GameRoom room = RoomRepository.createNewRoom(2, 3);
        String rid = room.roomId();
        room.bindToken("t0", 0);

        Session ws = sessionWithToken("t0");
        RoomEndpoint.seedRoomSessionForTest(rid, ws);
        RoomEndpoint endpoint = new RoomEndpoint(null);

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

        RoomEndpoint.dropRoomSessions(rid);
    }

    @Test
    void exceedingRatePerSecondIsSilentlyDropped() {
        GameRoom room = RoomRepository.createNewRoom(2, 3);
        String rid = room.roomId();
        room.bindToken("t0", 0);

        Session ws = sessionWithToken("t0");
        RoomEndpoint.seedRoomSessionForTest(rid, ws);
        RoomEndpoint endpoint = new RoomEndpoint(null);

        long before = room.state().getStateSeq();

        // 20 messaggi validi nello stesso secondo passano; il 21° è scartato.
        for (int i = 0; i < 21; i++) {
            endpoint.onMessage(ws, "ACTION:ackNotice:1", rid);
        }

        assertEquals(before + 20, room.state().getStateSeq(),
                "al massimo 20 messaggi al secondo vengono elaborati, il 21° è scartato");
        assertTrue(ws.isOpen(), "oltre soglia il messaggio è ignorato, la sessione non viene chiusa");

        RoomEndpoint.dropRoomSessions(rid);
    }
}
