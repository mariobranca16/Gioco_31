package it.gioco31.ws;

import jakarta.websocket.Session;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Quando una stanza viene rimossa dal repository, le sue eventuali sessioni
 * residue non devono restare appese nel registro dell'endpoint.
 */
class RoomEndpointSessionsTest {

    /** Sessione fittizia: implementa Session via proxy, close() è un no-op. */
    private static Session fakeSession() {
        return (Session) Proxy.newProxyInstance(
                RoomEndpointSessionsTest.class.getClassLoader(),
                new Class<?>[]{Session.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isOpen"  -> false;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals"  -> proxy == args[0];
                    default -> null; // close(...) e resto: nessuna azione
                });
    }

    @Test
    void dropRoomSessionsLeavesNoResidualEntry() {
        String rid = "ZZZZ";
        RoomEndpoint.seedRoomSessionForTest(rid, fakeSession());
        assertTrue(RoomEndpoint.hasRoomSessionsEntryForTest(rid));

        RoomEndpoint.dropRoomSessions(rid);

        assertFalse(RoomEndpoint.hasRoomSessionsEntryForTest(rid),
                "rimossa la stanza, il registro sessioni non deve conservare entry");
    }
}
