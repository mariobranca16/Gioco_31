package it.gioco31.ws;

import com.fasterxml.jackson.databind.JsonNode;
import it.gioco31.model.Phase;
import it.gioco31.room.GameRoom;
import it.gioco31.room.RoomRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Il flusso completo su un container vero: join HTTP, handshake del WebSocket
 * con la HttpSession appena creata, azioni di gioco e broadcast a due client,
 * riconnessione. È l'unico livello in cui vengono esercitati davvero
 * {@link HttpSessionConfigurator}, la mappatura {@code /ws/{roomId}} e il
 * trasporto: gli altri test dell'endpoint lavorano su sessioni finte e
 * chiamano i metodi annotati a mano.
 */
class RoomFlowEndToEndTest {

    private static EmbeddedApp app;

    private final List<String> rooms = new ArrayList<>();
    private final List<TestBrowser> browsers = new ArrayList<>();

    @BeforeAll
    static void startContainer() throws Exception {
        app = EmbeddedApp.start();
    }

    @AfterAll
    static void stopContainer() throws Exception {
        if (app != null) app.close();
    }

    @AfterEach
    void closeBrowsersAndRooms() {
        for (TestBrowser b : browsers) b.close();
        browsers.clear();
        // Le stanze nascono nel registro statico, condiviso da tutta la JVM.
        for (String rid : rooms) {
            RoomEndpoint.dropRoomSessions(rid);
            RoomRepository.remove(rid);
        }
        rooms.clear();
    }

    private TestBrowser browser() {
        TestBrowser b = new TestBrowser(app);
        browsers.add(b);
        return b;
    }

    private String createRoom(TestBrowser host, String name) throws Exception {
        String rid = host.createRoom(name, 2, 3);
        rooms.add(rid);
        return rid;
    }

    /** Attende una condizione sul server, che vive su thread suoi. */
    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(20);
        }
        fail("condizione mai raggiunta: " + what);
    }

    /** Attende che il server veda esattamente {@code expected} sessioni aperte. */
    private static void awaitOpenSockets(String rid, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        int seen = -1;
        while (System.nanoTime() < deadline) {
            seen = RoomEndpoint.openSessionCountForTest(rid);
            if (seen == expected) return;
            Thread.sleep(20);
        }
        fail("sessioni aperte attese: " + expected + ", viste: " + seen);
    }

    @Test
    void joiningAndOpeningTheSocketDeliversTheStateOfTheRoom() throws Exception {
        TestBrowser host = browser();
        String rid = createRoom(host, "Anna");

        assertNotNull(RoomRepository.get(rid), "il POST di creazione registra la stanza");

        TestBrowser.Socket socket = host.openSocket(rid);
        JsonNode state = socket.nextState();

        assertEquals(0, state.get("viewerIndex").asInt(), "l'host occupa il primo slot");
        assertTrue(state.get("viewerIsHost").asBoolean(), "chi crea la stanza è l'host");
        assertEquals(Phase.WAITING_FOR_PLAYERS.name(), state.get("phase").asText());
        assertEquals("Anna", state.get("players").get(0).get("name").asText());
        assertTrue(state.get("players").get(0).get("joined").asBoolean());
    }

    /**
     * La pagina della stanza e i moduli che carica. Il valore sta nel fatto che
     * qui la JSP viene compilata davvero e i file statici passano dal
     * dispatcher "default": un import verso un file che non esiste è un errore
     * che si vedrebbe solo aprendo il browser.
     */
    @Test
    void theRoomPageLoadsTheJavascriptModules() throws Exception {
        TestBrowser host = browser();
        String rid = createRoom(host, "Anna");

        String page = host.getPage("/room?room=" + rid);
        assertTrue(page.contains("type=\"module\""), "la pagina carica un modulo ES");
        assertTrue(page.contains("/js/main.js"), "il punto d'ingresso è main.js");

        for (String module : List.of("main.js", "config.js", "state.js", "util.js",
                "fx.js", "ui.js", "view.js", "render.js", "net.js", "actions.js")) {
            assertEquals(200, host.statusOf("/js/" + module), "modulo non servito: " + module);
        }
    }

    /**
     * L'heartbeat del client è utile solo se qualcosa torna indietro: è da
     * quella risposta che il client capisce che il socket è ancora vivo.
     */
    @Test
    void theHeartbeatGetsAnAnswer() throws Exception {
        TestBrowser host = browser();
        String rid = createRoom(host, "Anna");

        TestBrowser.Socket socket = host.openSocket(rid);
        socket.nextState();

        socket.send("ping");
        JsonNode pong = socket.nextState();

        assertTrue(pong.path("pong").asBoolean(), "il ping deve tornare indietro come pong");
        assertTrue(pong.path("stateSeq").isMissingNode(),
                "il pong non è uno stato: il client lo scarta senza renderizzarlo");
        assertTrue(socket.isOpen());
    }

    @Test
    void aSocketWithoutAJoinIsRefused() throws Exception {
        TestBrowser host = browser();
        String rid = createRoom(host, "Anna");

        // Nessun join: il browser non ha HttpSession, quindi il configurator
        // non ha niente da passare all'endpoint.
        TestBrowser stranger = browser();
        TestBrowser.Socket socket = stranger.openSocket(rid);

        assertTrue(socket.awaitClose(), "la connessione senza join va chiusa dal server");
        assertEquals("No HTTP session", socket.closeReason().getReasonPhrase());
    }

    @Test
    void aSocketOnAnotherRoomIsRefused() throws Exception {
        TestBrowser anna = browser();
        String annaRoom = createRoom(anna, "Anna");

        TestBrowser bruno = browser();
        String brunoRoom = createRoom(bruno, "Bruno");

        // Sessione valida, ma per un'altra stanza.
        TestBrowser.Socket socket = anna.openSocket(brunoRoom);

        assertTrue(socket.awaitClose(), "la stanza sbagliata va rifiutata");
        assertEquals("Not joined", socket.closeReason().getReasonPhrase());
        assertNotNull(RoomRepository.get(annaRoom));
    }

    @Test
    void aMoveIsBroadcastToEveryClientOfTheRoom() throws Exception {
        TestBrowser anna = browser();
        String rid = createRoom(anna, "Anna");
        TestBrowser bruno = browser();
        bruno.joinRoom("Bruno", rid);

        TestBrowser.Socket annaWs = anna.openSocket(rid);
        TestBrowser.Socket brunoWs = bruno.openSocket(rid);
        awaitOpenSockets(rid, 2);

        // L'avvio arriva a tutti i socket già aperti: nessuno deve ricaricare
        // la pagina per accorgersi che la partita è cominciata.
        anna.startMatch(rid);

        JsonNode annaState = annaWs.nextStateWithPhase(Phase.PLAYING.name());
        JsonNode brunoState = brunoWs.nextStateWithPhase(Phase.PLAYING.name());
        assertEquals(3, annaState.get("viewHand").size(), "tre carte in mano a testa");
        assertEquals(3, brunoState.get("viewHand").size(),
                "anche chi non ha premuto Avvia riceve la partita avviata");

        // Gioca chi è di turno; l'altro deve comunque ricevere il nuovo stato.
        int current = annaState.get("currentIndex").asInt();
        TestBrowser.Socket mover = (current == annaState.get("viewerIndex").asInt()) ? annaWs : brunoWs;
        TestBrowser.Socket watcher = (mover == annaWs) ? brunoWs : annaWs;

        long seqBefore = annaState.get("stateSeq").asLong();
        int deckBefore = annaState.get("deckSize").asInt();
        mover.send("drawDeck");

        JsonNode afterMover = mover.nextStateAfter(seqBefore);
        JsonNode afterWatcher = watcher.nextStateAfter(seqBefore);

        assertEquals(deckBefore - 1, afterMover.get("deckSize").asInt(), "una carta in meno nel mazzo");
        assertEquals(afterMover.get("stateSeq").asLong(), afterWatcher.get("stateSeq").asLong(),
                "lo stesso broadcast, quindi la stessa stateSeq per tutti");
        assertFalse(afterMover.get("viewPending").isNull(),
                "chi ha pescato vede la carta in sospeso");
        assertTrue(afterWatcher.get("viewPending").isNull(),
                "l'avversario non vede la carta pescata");
    }

    @Test
    void reconnectingKeepsTheSeatAndTheHand() throws Exception {
        TestBrowser anna = browser();
        String rid = createRoom(anna, "Anna");
        TestBrowser bruno = browser();
        bruno.joinRoom("Bruno", rid);

        anna.openSocket(rid);
        TestBrowser.Socket brunoWs = bruno.openSocket(rid);
        JsonNode before = brunoWs.nextState();
        int seat = before.get("viewerIndex").asInt();

        // Cade la rete di Bruno.
        brunoWs.close();
        awaitOpenSockets(rid, 1);

        // Riapre entro la grazia, con la stessa HttpSession: stesso posto.
        TestBrowser.Socket reopened = bruno.openSocket(rid);
        JsonNode after = reopened.nextState();

        assertEquals(seat, after.get("viewerIndex").asInt(), "riprende il suo posto");
        assertEquals("Bruno", after.get("players").get(seat).get("name").asText());

        GameRoom room = RoomRepository.get(rid);
        assertTrue(room.sweepDisconnected(System.currentTimeMillis() + 60 * 60 * 1000L).isEmpty(),
                "riconnesso: la scadenza della grazia è stata annullata");
    }

    @Test
    void aDisconnectedPlayerIsRemovedOnceTheGraceExpires() throws Exception {
        TestBrowser anna = browser();
        String rid = createRoom(anna, "Anna");
        TestBrowser bruno = browser();
        bruno.joinRoom("Bruno", rid);

        TestBrowser.Socket annaWs = anna.openSocket(rid);
        TestBrowser.Socket brunoWs = bruno.openSocket(rid);
        brunoWs.nextState();
        awaitOpenSockets(rid, 2);
        assertTrue(annaWs.isOpen(), "l'ingresso di Bruno non deve far cadere il socket di Anna");

        brunoWs.close();
        awaitOpenSockets(rid, 1);

        // Il tempo è iniettato: nessuna attesa reale di 45 secondi.
        GameRoom room = RoomRepository.get(rid);
        await("la grazia avviata dall'onClose", () ->
                !room.sweepDisconnected(System.currentTimeMillis() + 60 * 60 * 1000L).isEmpty());

        assertFalse(room.state().getPlayers().get(1).isJoined(),
                "scaduta la grazia, lo slot torna libero");
    }
}
