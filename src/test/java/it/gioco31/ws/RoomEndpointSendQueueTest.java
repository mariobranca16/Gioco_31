package it.gioco31.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.gioco31.room.GameRoom;
import jakarta.websocket.SendResult;
import jakarta.websocket.Session;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Invii serializzati per sessione. L'async remote di Tomcat rifiuta un secondo
 * {@code sendText} mentre il primo è ancora in scrittura: senza coda, due
 * broadcast ravvicinati (un giocatore che entra mentre un altro sta ancora
 * ricevendo il suo stato) facevano chiudere una sessione sana come se avesse
 * avuto un errore di I/O.
 */
class RoomEndpointSendQueueTest extends EndpointTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static long stateSeqOf(String json) throws Exception {
        return MAPPER.readTree(json).get("stateSeq").asLong();
    }

    @Test
    void onlyOneSendIsInFlightAndTheLatestStateWins() throws Exception {
        GameRoom room = newRoom(2);
        String rid = room.roomId();
        room.bindToken("t0", 0);

        List<Sent> sent = new ArrayList<>();
        Session ws = recordingSession("t0", sent);
        RoomEndpoint.seedRoomSessionForTest(rid, ws);

        RoomEndpoint.broadcastRoom(rid);
        assertEquals(1, sent.size(), "il primo stato parte subito");
        long firstSeq = stateSeqOf(sent.get(0).json());

        // Altri due broadcast mentre il primo è ancora in scrittura: nessun
        // secondo sendText, che il remote vero rifiuterebbe.
        RoomEndpoint.broadcastRoom(rid);
        RoomEndpoint.broadcastRoom(rid);
        assertEquals(1, sent.size(), "un solo invio in volo per sessione");
        assertTrue(ws.isOpen(), "la sessione non va chiusa: non c'è nessun errore di I/O");

        // Concluso il primo, parte il più recente: gli stati intermedi non
        // servono, ogni messaggio è un'istantanea completa.
        sent.get(0).handler().onResult(new SendResult(ws));
        assertEquals(2, sent.size(), "finito il primo invio parte quello accodato");
        assertEquals(firstSeq + 2, stateSeqOf(sent.get(1).json()),
                "viene spedito l'ultimo stato, non quello intermedio");

        // Niente altro in coda: la catena si ferma.
        sent.get(1).handler().onResult(new SendResult(ws));
        assertEquals(2, sent.size(), "coda vuota, nessun invio ulteriore");
    }

    @Test
    void aFailedSendClosesTheSessionAndDropsWhatWasQueued() throws Exception {
        GameRoom room = newRoom(2);
        String rid = room.roomId();
        room.bindToken("t0", 0);

        List<Sent> sent = new ArrayList<>();
        Session ws = recordingSession("t0", sent);
        RoomEndpoint.seedRoomSessionForTest(rid, ws);

        RoomEndpoint.broadcastRoom(rid);
        RoomEndpoint.broadcastRoom(rid); // accodato

        sent.get(0).handler().onResult(new SendResult(ws, new IOException("rete caduta")));

        assertEquals(1, sent.size(), "invio fallito: quello accodato non parte");
        awaitClosed(ws);
    }

    /** La chiusura è delegata al closer: non è immediata. */
    private static void awaitClosed(Session ws) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (!ws.isOpen()) return;
            Thread.sleep(10);
        }
        fail("la sessione che non riceve più deve essere chiusa");
    }
}
