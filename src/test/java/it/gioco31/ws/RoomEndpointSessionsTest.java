package it.gioco31.ws;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Quando una stanza viene rimossa dal repository, le sue eventuali sessioni
 * residue non devono restare appese nel registro dell'endpoint.
 */
class RoomEndpointSessionsTest extends EndpointTestBase {

    @Test
    void dropRoomSessionsLeavesNoResidualEntry() {
        String rid = "ZZZZ";
        trackRoom(rid); // ripulito comunque, anche se un'asserzione fallisce prima
        RoomEndpoint.seedRoomSessionForTest(rid, sessionWithToken("t0"));
        assertTrue(RoomEndpoint.hasRoomSessionsEntryForTest(rid));

        RoomEndpoint.dropRoomSessions(rid);

        assertFalse(RoomEndpoint.hasRoomSessionsEntryForTest(rid),
                "rimossa la stanza, il registro sessioni non deve conservare entry");
    }
}
