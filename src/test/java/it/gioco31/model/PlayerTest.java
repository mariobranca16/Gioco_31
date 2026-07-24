package it.gioco31.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlayerTest {

    private static Player withHand(Card... cards) {
        Player p = new Player("Tester", 3);
        p.getHand().addAll(List.of(cards));
        return p;
    }

    @Test
    void bestSameSuitScorePicksHighestSuitSum() {
        Player p = withHand(
                new Card(Suit.DENARI, Rank.RE),      // denari: 10
                new Card(Suit.COPPE, Rank.ASSO),     // coppe: 11 + 7 = 18
                new Card(Suit.COPPE, Rank.SETTE));

        Player.BestSuitScore best = p.bestSameSuitScore();

        assertEquals(Suit.COPPE, best.getSuit());
        assertEquals(18, best.getValue());
    }

    @Test
    void bestSameSuitScoreOnEmptyHandIsZero() {
        Player p = withHand();
        Player.BestSuitScore best = p.bestSameSuitScore();
        assertNull(best.getSuit());
        assertEquals(0, best.getValue());
    }

    @Test
    void hasThirtyOneOnlyAtExactlyThirtyOne() {
        Player p31 = withHand(
                new Card(Suit.SPADE, Rank.ASSO),
                new Card(Suit.SPADE, Rank.RE),
                new Card(Suit.SPADE, Rank.FANTE));
        assertTrue(p31.hasThirtyOne());

        Player p30 = withHand(
                new Card(Suit.SPADE, Rank.RE),
                new Card(Suit.SPADE, Rank.CAVALLO),
                new Card(Suit.SPADE, Rank.FANTE));
        assertFalse(p30.hasThirtyOne());
    }

    @Test
    void nameValidationRejectsInvalidNames() {
        assertThrows(IllegalArgumentException.class, () -> new Player(null, 3));
        assertThrows(IllegalArgumentException.class, () -> new Player("a", 3));
        assertThrows(IllegalArgumentException.class, () -> new Player("x".repeat(17), 3));
        assertThrows(IllegalArgumentException.class, () -> new Player("bad<script>", 3));
    }

    @Test
    void nameValidationAcceptsAndTrimsValidNames() {
        Player p = new Player("  Mario_16 ", 3);
        assertEquals("Mario_16", p.getName());
    }
}
