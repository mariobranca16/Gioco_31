package it.gioco31.ws;

import it.gioco31.model.Phase;
import it.gioco31.room.GameRoom;
import jakarta.websocket.Session;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Un'azione che non cambia lo stato non deve ritrasmettere. Il broadcast
 * costa un JSON per ogni sessione sotto il lock della stanza e incrementa
 * stateSeq, che è il segnale con cui i client decidono di ridisegnarsi:
 * mandarlo a vuoto fa lavorare tutto il tavolo per nulla.
 */
class RoomEndpointNoOpBroadcastTest extends EndpointTestBase {

    private final RoomEndpoint endpoint = new RoomEndpoint();

    /** Siede un giocatore e registra la sua sessione nella stanza. */
    private static Session seat(GameRoom room, String token, int index) {
        room.bindToken(token, index);
        Session ws = sessionWithToken(token);
        RoomEndpoint.seedRoomSessionForTest(room.roomId(), ws);
        return ws;
    }

    @Test
    void unknownActionDoesNotBroadcast() {
        GameRoom room = newRoom(2);
        Session ws = seat(room, "t0", 0);

        long before = room.state().getStateSeq();
        endpoint.onMessage(ws, action("doesNotExist"), room.roomId());

        assertEquals(before, room.state().getStateSeq(),
                "un'azione sconosciuta cade nel default e non cambia nulla");
    }

    @Test
    void ackOfANoticeThatIsNotThereDoesNotBroadcast() {
        GameRoom room = newRoom(2);
        Session ws = seat(room, "t0", 0);

        long before = room.state().getStateSeq();

        // Nessun avviso in sospeso: l'ack non ha niente da togliere.
        endpoint.onMessage(ws, action("ackNotice", 999), room.roomId());
        assertEquals(before, room.state().getStateSeq(),
                "un ack senza avviso corrispondente è un no-op");

        // Nemmeno un id non numerico deve provocare un giro di broadcast.
        endpoint.onMessage(ws, "{\"action\":\"ackNotice\",\"arg\":\"abc\"}", room.roomId());
        assertEquals(before, room.state().getStateSeq(), "id non numerico: ignorato");

        // Con l'avviso davvero presente, invece, lo stato cambia e si ritrasmette.
        endpoint.onMessage(ws, armedAckNotice(room, 0), room.roomId());
        assertTrue(room.state().getStateSeq() > before,
                "l'ack di un avviso esistente lo rimuove: va ritrasmesso");
        assertNull(room.state().getNoticeForPlayer(0));
    }

    /** Input non conforme al protocollo: ignorato in silenzio, come il resto. */
    @Test
    void malformedMessagesDoNotBroadcast() {
        GameRoom room = newRoom(2);
        Session ws = seat(room, "t0", 0);

        long before = room.state().getStateSeq();

        for (String junk : List.of(
                "non sono JSON",
                "[\"drawDeck\"]",          // JSON valido, ma non un oggetto
                "42",
                "{}",                      // oggetto senza azione
                "{\"action\":\"\"}",
                "{\"azione\":\"drawDeck\"}")) {
            endpoint.onMessage(ws, junk, room.roomId());
        }

        assertEquals(before, room.state().getStateSeq(),
                "niente di tutto questo è un'azione: nessun broadcast");
    }

    @Test
    void moveFromAPlayerWhoIsNotOnTurnDoesNotBroadcast() {
        GameRoom room = newRoom(2);
        seat(room, "t0", 0);
        Session other = seat(room, "t1", 1);

        room.state().setPhase(Phase.PLAYING);
        room.state().setCurrentIndex(0);

        long before = room.state().getStateSeq();
        endpoint.onMessage(other, action("drawDeck"), room.roomId());

        assertEquals(before, room.state().getStateSeq(),
                "la mossa di chi non è di turno è respinta prima di toccare lo stato");
    }

    @Test
    void actionsInLobbyDoNotBroadcast() {
        GameRoom room = newRoom(2);
        Session ws = seat(room, "t0", 0);

        assertEquals(Phase.WAITING_FOR_PLAYERS, room.state().getPhase());

        long before = room.state().getStateSeq();
        endpoint.onMessage(ws, action("knock"), room.roomId());

        assertEquals(before, room.state().getStateSeq(),
                "in lobby la guardia di fase respinge la mossa: niente da ritrasmettere");
    }

    @Test
    void restartRequestedByANonHostDoesNotBroadcast() {
        GameRoom room = newRoom(2);
        seat(room, "t0", 0);
        Session notHost = seat(room, "t1", 1);

        room.state().setPhase(Phase.GAME_OVER);

        long before = room.state().getStateSeq();
        endpoint.onMessage(notHost, action("restartGame"), room.roomId());

        assertEquals(before, room.state().getStateSeq(),
                "solo l'host può far ripartire la partita");
    }

    @Test
    void aRejectedMoveStillBroadcastsBecauseItLeavesANotice() {
        GameRoom room = newRoom(2);
        Session ws = seat(room, "t0", 0);

        room.state().setPhase(Phase.PLAYING);
        room.state().setCurrentIndex(0);

        // Scarti vuoti: il motore rifiuta, ma il rifiuto diventa un avviso per
        // il giocatore — che va recapitato, quindi il broadcast ci vuole.
        long before = room.state().getStateSeq();
        endpoint.onMessage(ws, action("drawDiscard"), room.roomId());

        assertTrue(room.state().getStateSeq() > before,
                "il rifiuto produce un avviso: quello va ritrasmesso");
        assertNotNull(room.state().getNoticeForPlayer(0),
                "il messaggio del motore è finito nell'avviso del giocatore");
    }
}
