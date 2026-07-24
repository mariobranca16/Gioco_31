package it.gioco31.testutil;

import it.gioco31.model.Card;
import it.gioco31.model.GameState;
import it.gioco31.model.Player;
import it.gioco31.model.Rank;
import it.gioco31.model.Suit;
import it.gioco31.room.GameRoom;

import java.util.ArrayList;
import java.util.List;

/**
 * Fixture condivise dai test: giocatori seduti, stati di gioco a seed
 * fisso, stanze con token legati e mani dal punteggio noto.
 */
public final class Fixtures {

    private Fixtures() {}

    /** Giocatore già seduto al tavolo, 3 vite. */
    public static Player player(String name) {
        Player p = new Player(name, 3);
        p.setJoined(true);
        return p;
    }

    /** Stato con n giocatori "Player1..N" già seduti, seed fisso 42, 3 vite. */
    public static GameState newGame(int nPlayers) {
        List<Player> players = new ArrayList<>();
        for (int i = 0; i < nPlayers; i++) players.add(player("Player" + (i + 1)));
        return new GameState(players, 42L, 3);
    }

    /** Stanza "TEST" con n giocatori seduti e token "t0".."tN-1" legati agli slot. */
    public static GameRoom newRoom(int nPlayers) {
        GameRoom room = new GameRoom("TEST", newGame(nPlayers));
        for (int i = 0; i < nPlayers; i++) room.bindToken("t" + i, i);
        return room;
    }

    public static Card c(Suit s, Rank r) {
        return new Card(s, r);
    }

    public static void setHand(Player p, Card... cards) {
        p.getHand().clear();
        p.getHand().addAll(List.of(cards));
    }

    /** Mano da 10 punti (semi diversi, il migliore è 10). */
    public static void hand10(Player p) {
        setHand(p, c(Suit.BASTONI, Rank.RE), c(Suit.SPADE, Rank.DUE), c(Suit.COPPE, Rank.TRE));
    }

    /** Mano da 25 punti in denari. */
    public static void hand25(Player p) {
        setHand(p, c(Suit.DENARI, Rank.RE), c(Suit.DENARI, Rank.FANTE), c(Suit.DENARI, Rank.CINQUE));
    }

    /** Mano da 30 punti in coppe. */
    public static void hand30(Player p) {
        setHand(p, c(Suit.COPPE, Rank.RE), c(Suit.COPPE, Rank.FANTE), c(Suit.COPPE, Rank.CAVALLO));
    }
}
