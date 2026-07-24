package it.gioco31.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.gioco31.model.Card;
import it.gioco31.model.GameState;
import it.gioco31.model.Phase;
import it.gioco31.model.Rank;
import it.gioco31.model.Suit;
import it.gioco31.service.ThirtyOneEngine;
import org.junit.jupiter.api.Test;

import java.util.List;

import static it.gioco31.testutil.Fixtures.newGame;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Fissa il contratto del JSON di stato inviato ai client:
 * campi presenti, tipi e regole di visibilità delle carte.
 */
class RoomEndpointJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static GameState playingState(int nPlayers) {
        GameState s = newGame(nPlayers);
        new ThirtyOneEngine().startRound(s); // dealer 0 -> current 1
        return s;
    }

    private static JsonNode parse(GameState s, int viewer, boolean isHost) throws Exception {
        return MAPPER.readTree(RoomEndpoint.buildStateJson(s, viewer, isHost));
    }

    @Test
    void containsCoreFieldsWithExpectedTypes() throws Exception {
        GameState s = playingState(3);
        JsonNode root = parse(s, 0, true);

        assertEquals("PLAYING", root.get("phase").asText());
        assertEquals(0, root.get("viewerIndex").asInt());
        assertEquals(s.getCurrentIndex(), root.get("currentIndex").asInt());
        assertTrue(root.get("winnerIndex").isNull());
        assertEquals(s.getDeck().size(), root.get("deckSize").asInt());
        assertTrue(root.get("viewerIsHost").asBoolean());
        assertFalse(root.get("viewerEliminated").asBoolean());
        assertEquals(3, root.get("players").size());

        JsonNode discardTop = root.get("discardTop");
        assertTrue(discardTop.isObject());
        assertTrue(discardTop.has("suit"));
        assertTrue(discardTop.has("rank"));
        assertTrue(discardTop.has("value"));
        assertTrue(discardTop.has("label"));
    }

    @Test
    void viewerSeesOwnHandOthersOnlyCardCount() throws Exception {
        GameState s = playingState(3);
        int viewer = 0;
        JsonNode root = parse(s, viewer, false);

        JsonNode viewHand = root.get("viewHand");
        assertEquals(3, viewHand.size());
        assertEquals(s.getPlayers().get(viewer).getHand().get(0).label(),
                viewHand.get(0).get("label").asText());

        for (JsonNode p : root.get("players")) {
            assertEquals(3, p.get("cardCount").asInt());
            assertFalse(p.has("hand"), "le mani altrui non devono mai essere serializzate");
        }
    }

    @Test
    void pendingDrawVisibleOnlyToCurrentPlayer() throws Exception {
        GameState s = playingState(3);
        int current = s.getCurrentIndex();
        s.setPendingDraw(new Card(Suit.DENARI, Rank.ASSO));

        JsonNode forCurrent = parse(s, current, false);
        assertEquals("ASSO", forCurrent.get("viewPending").get("rank").asText());
        assertEquals("ASSO", forCurrent.get("players").get(current).get("pendingDraw").get("rank").asText());

        int other = (current + 1) % 3;
        JsonNode forOther = parse(s, other, false);
        assertTrue(forOther.get("viewPending").isNull(),
                "la carta pescata non deve essere visibile agli altri giocatori");
        assertTrue(forOther.get("players").get(current).get("pendingDraw").isNull());
    }

    @Test
    void eliminatedViewerWatchesCurrentPlayersHand() throws Exception {
        GameState s = playingState(3);
        int current = s.getCurrentIndex();
        int viewer = (current + 1) % 3;
        s.getPlayers().get(viewer).setEliminated(true);
        s.setPendingDraw(new Card(Suit.COPPE, Rank.RE));

        JsonNode root = parse(s, viewer, false);

        assertTrue(root.get("viewerEliminated").asBoolean());
        assertEquals(current, root.get("handViewIndex").asInt());
        assertEquals(s.getPlayers().get(current).getHand().get(0).label(),
                root.get("viewHand").get(0).get("label").asText());
        assertEquals("RE", root.get("viewPending").get("rank").asText());
    }

    @Test
    void noticeIsDeliveredOnlyToItsRecipientAndEscaped() throws Exception {
        GameState s = playingState(2);
        String tricky = "linea1\nlinea2 \"quote\" \\slash <tag>";
        s.setNoticeForPlayer(0, tricky);

        JsonNode forViewer0 = parse(s, 0, false);
        assertEquals(tricky, forViewer0.get("players").get(0).get("noticeMsg").asText());
        assertFalse(forViewer0.get("players").get(0).get("noticeId").isNull());

        JsonNode forViewer1 = parse(s, 1, false);
        assertTrue(forViewer1.get("players").get(0).get("noticeMsg").isNull());
        assertTrue(forViewer1.get("players").get(1).get("noticeMsg").isNull());
    }

    @Test
    void serializesTurnTimerAndEvents() throws Exception {
        GameState s = playingState(2);
        JsonNode root = parse(s, 0, false);

        assertTrue(root.get("turnSecondsLeft").asInt() > 0,
                "a partita in corso il timer di turno è attivo");
        assertTrue(root.get("events").isArray());
        assertTrue(root.get("events").size() > 0, "startRound registra un evento");
        assertTrue(root.get("roundResult").isNull(), "nessun esito prima della fine del round");

        s.setPhase(Phase.WAITING_FOR_PLAYERS);
        assertTrue(parse(s, 0, false).get("turnSecondsLeft").isNull(),
                "fuori dal gioco il timer non va esposto");
    }

    @Test
    void roundResultSerializesRevealedHands() throws Exception {
        GameState s = playingState(2);
        s.setRoundResult("esito di prova", List.of(
                new GameState.RevealedHand(0, "Player1", 25, true, false,
                        List.of(new Card(Suit.DENARI, Rank.RE)))));

        JsonNode rr = parse(s, 0, false).get("roundResult");

        assertEquals("esito di prova", rr.get("message").asText());
        assertEquals(1, rr.get("hands").size());
        JsonNode h = rr.get("hands").get(0);
        assertEquals("Player1", h.get("name").asText());
        assertEquals(25, h.get("score").asInt());
        assertTrue(h.get("lostLife").asBoolean());
        assertEquals("RE", h.get("cards").get(0).get("rank").asText());
    }

    @Test
    void roundResultExposesItsAgeForReloadRecovery() throws Exception {
        GameState s = playingState(2);
        s.setRoundResult("esito", List.of());

        JsonNode rr = parse(s, 0, false).get("roundResult");

        assertTrue(rr.has("ageMs"), "il client deve poter capire quanto è recente l'esito");
        assertTrue(rr.get("ageMs").asLong() >= 0);
        assertTrue(rr.get("ageMs").asLong() < 5_000, "un esito appena creato ha età ~0");
    }

    @Test
    void gameOverSerializesWinnerIndex() throws Exception {
        GameState s = playingState(2);
        s.setPhase(Phase.GAME_OVER);
        s.setWinnerIndex(1);

        JsonNode root = parse(s, 0, true);

        assertEquals("GAME_OVER", root.get("phase").asText());
        assertEquals(1, root.get("winnerIndex").asInt());
    }
}
