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
 * Il ping è solo heartbeat: non deve toccare lo stato né generare un broadcast
 * (che incrementerebbe stateSeq). Un'azione qualsiasi, invece, ritrasmette.
 */
class RoomEndpointPingTest {

    /** Sessione fittizia via proxy: porta il token, getAsyncRemote resta null. */
    private static Session sessionWithToken(String token) {
        Map<String, Object> props = new HashMap<>();
        props.put("token", token);
        return (Session) Proxy.newProxyInstance(
                RoomEndpointPingTest.class.getClassLoader(),
                new Class<?>[]{Session.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUserProperties" -> props;
                    case "isOpen"   -> true;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals"   -> proxy == args[0];
                    // getAsyncRemote() == null: l'eventuale invio fallisce ed è
                    // assorbito dal broadcast; a noi basta osservare stateSeq.
                    default -> null;
                });
    }

    @Test
    void pingDoesNotBumpStateSeqNorBroadcast() {
        GameRoom room = RoomRepository.createNewRoom(2, 3);
        String rid = room.roomId();
        room.bindToken("t0", 0);

        Session ws = sessionWithToken("t0");
        RoomEndpoint.seedRoomSessionForTest(rid, ws);

        RoomEndpoint endpoint = new RoomEndpoint(null);
        long before = room.state().getStateSeq();

        // Il ping non deve ritrasmettere: stateSeq invariato.
        endpoint.onMessage(ws, "ACTION:ping", rid);
        assertEquals(before, room.state().getStateSeq(),
                "il ping non deve incrementare stateSeq né provocare un broadcast");

        // Un'azione non-ping (anche innocua) ritrasmette: stateSeq cresce.
        endpoint.onMessage(ws, "ACTION:ackNotice:1", rid);
        assertTrue(room.state().getStateSeq() > before,
                "un'azione non-ping produce un broadcast e incrementa stateSeq");

        RoomEndpoint.dropRoomSessions(rid); // pulizia del registro statico condiviso
    }
}
