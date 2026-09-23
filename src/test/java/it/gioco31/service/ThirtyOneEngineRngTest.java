package it.gioco31.service;

import it.gioco31.model.Card;
import it.gioco31.model.GameState;
import it.gioco31.model.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Il generatore del motore è di istanza, non condiviso tra le stanze.
 * SecureRandom sincronizza engineNextBytes sull'oggetto: con un'unica istanza
 * statica ogni mescolata e ogni ricarica di qualsiasi stanza si sarebbe messa
 * in coda dietro lo stesso monitor, per giunta con room.lock() già in mano.
 */
class ThirtyOneEngineRngTest {

    private static GameState stateWith(int players) {
        List<Player> list = new ArrayList<>(players);
        for (int i = 0; i < players; i++) list.add(new Player("P" + i, 3));
        return new GameState(list, 3);
    }

    @Test
    void theGeneratorIsPerEngineNotShared() throws Exception {
        Field rng = ThirtyOneEngine.class.getDeclaredField("rng");
        assertFalse(Modifier.isStatic(rng.getModifiers()),
                "un generatore statico serializzerebbe le mescolate di tutte le stanze");

        rng.setAccessible(true);
        assertNotSame(rng.get(new ThirtyOneEngine()), rng.get(new ThirtyOneEngine()),
                "due motori non devono condividere il generatore");
    }

    @Test
    void eachEngineStillDealsAFullShuffledDeck() {
        // Il passaggio a istanza non deve cambiare quel che il motore produce:
        // 40 carte distinte tra mani, mazzo e scarti.
        for (int round = 0; round < 5; round++) {
            GameState s = stateWith(4);
            new ThirtyOneEngine().startRound(s);

            List<Card> all = new ArrayList<>();
            for (Player p : s.getPlayers()) all.addAll(p.getHand());
            all.addAll(s.getDiscard());
            while (!s.getDeck().isEmpty()) all.add(s.getDeck().draw());

            assertEquals(40, all.size(), "le 40 carte ci sono tutte");
            assertEquals(40, all.stream().distinct().count(), "nessun duplicato");
        }
    }
}
