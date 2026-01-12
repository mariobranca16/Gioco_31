package it.gioco31.service;

import it.gioco31.model.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public final class ThirtyOneEngine {

    public void startRound(GameState s) {
        s.setWinnerIndex(null);

        Random rng = new Random(s.getSeed() + System.nanoTime());
        s.setDeck(Deck.newNeapolitan40(rng));
        s.getDiscard().clear();
        s.setPendingDraw(null);
        s.setFinalTurnsRemaining(0);
        s.setKnockerIndex(null);
        s.setPhase(Phase.PLAYING);

        for (Player p : s.getPlayers()) p.getHand().clear();

        for (int i = 0; i < 3; i++) {
            for (Player p : s.getPlayers()) {
                if (!p.isEliminated()) p.getHand().add(s.getDeck().draw());
            }
        }

        s.getDiscard().push(s.getDeck().draw());
        s.setCurrentIndex(nextActivePlayerIndex(s, s.getDealerIndex()));
    }

    public void knock(GameState s) {
        ensurePhase(s, Phase.PLAYING);
        if (s.getPendingDraw() != null) throw new IllegalStateException("Prima devi decidere sulla carta pescata.");
        s.setKnockerIndex(s.getCurrentIndex());

        int remaining = Math.max(0, countActivePlayers(s) - 1);
        s.setFinalTurnsRemaining(remaining);
        s.setPhase(Phase.KNOCK_CALLED);

        String kn = safeName(s, s.getKnockerIndex());
        String msg = (remaining > 0)
                ? ("🔔 " + kn + " ha bussato! Restano " + remaining + " turno/i finali.")
                : ("🔔 " + kn + " ha bussato!");
        for (int i = 0; i < s.getPlayers().size(); i++) {
            s.setNoticeForPlayer(i, msg);
        }

        if (remaining <= 0) {
            resolveEndOfKnockRound(s);
            return;
        }

        s.setCurrentIndex(nextActivePlayerIndex(s, s.getCurrentIndex()));
    }

    public void drawPendingFromDeck(GameState s) {
        ensureActionAllowed(s);
        if (s.getPendingDraw() != null) throw new IllegalStateException("Hai già una carta pescata.");
        refillDeckIfNeeded(s, new Random(s.getSeed() + System.nanoTime()));
        s.setPendingDraw(s.getDeck().draw());
    }

    public void drawPendingFromDiscard(GameState s) {
        ensureActionAllowed(s);
        if (s.getPendingDraw() != null) throw new IllegalStateException("Hai già una carta pescata.");
        if (s.getDiscard().isEmpty()) throw new IllegalStateException("Scarti vuoti");
        s.setPendingDraw(s.getDiscard().pop());
    }

    public boolean takeAndDiscard(GameState s, int handIndexToDiscard) {
        ensureActionAllowed(s);
        if (s.getPendingDraw() == null) throw new IllegalStateException("Nessuna carta pescata.");
        Player p = s.getPlayers().get(s.getCurrentIndex());
        if (handIndexToDiscard < 0 || handIndexToDiscard > 2) throw new IllegalArgumentException("Indice scarto errato.");

        Card old = p.getHand().set(handIndexToDiscard, s.getPendingDraw());
        s.getDiscard().push(old);
        s.setPendingDraw(null);

        if (!p.isEliminated() && p.hasThirtyOne()) return true;

        advanceTurn(s);
        return false;
    }

    public void rejectDraw(GameState s) {
        ensureActionAllowed(s);
        if (s.getPendingDraw() == null) throw new IllegalStateException("Nessuna carta pescata.");
        s.getDiscard().push(s.getPendingDraw());
        s.setPendingDraw(null);

        advanceTurn(s);
    }

    private void ensureActionAllowed(GameState s) {
        if (s.getPhase() == Phase.GAME_OVER || s.getPhase() == Phase.WAITING_FOR_PLAYERS)
            throw new IllegalStateException("Azione non valida: partita non in corso.");

        if (s.getKnockerIndex() != null
                && s.getCurrentIndex() == s.getKnockerIndex()
                && s.getPhase() == Phase.KNOCK_CALLED)
            throw new IllegalStateException("Chi ha bussato non gioca nel turno finale.");
    }

    private void ensurePhase(GameState s, Phase expected) {
        if (s.getPhase() != expected) throw new IllegalStateException("Fase non valida: " + s.getPhase());
    }

    private int scoreHand(List<Card> hand) {
        int best = 0;
        for (Suit suit : Suit.values()) {
            int sum = 0;
            for (Card c : hand) if (c.suit() == suit) sum += c.value();
            best = Math.max(best, sum);
        }
        return best;
    }

    private void advanceTurn(GameState s) {
        if (s.getPhase() == Phase.KNOCK_CALLED) {
            int rem = s.getFinalTurnsRemaining() - 1;
            s.setFinalTurnsRemaining(rem);

            if (rem <= 0) {
                resolveEndOfKnockRound(s);
                return;
            }
        }

        s.setCurrentIndex(nextActivePlayerIndex(s, s.getCurrentIndex()));
    }

    private void resolveEndOfKnockRound(GameState s) {
        Map<Integer, Integer> scoreByIdx = new LinkedHashMap<>();
        for (int i = 0; i < s.getPlayers().size(); i++) {
            Player p = s.getPlayers().get(i);
            if (!p.isEliminated()) scoreByIdx.put(i, scoreHand(p.getHand()));
        }

        if (scoreByIdx.isEmpty()) {
            s.setPhase(Phase.GAME_OVER);
            s.setWinnerIndex(null);
            s.clearAllNotices();
            return;
        }

        Integer k = s.getKnockerIndex();
        boolean knockerValid = (k != null && scoreByIdx.containsKey(k));
        int kScore = knockerValid ? scoreByIdx.get(k) : Integer.MIN_VALUE;

        List<Integer> tied = new ArrayList<>();
        List<Integer> beat = new ArrayList<>();

        if (knockerValid) {
            for (var e : scoreByIdx.entrySet()) {
                int idx = e.getKey();
                int sc = e.getValue();
                if (idx == k) continue;

                if (sc == kScore) tied.add(idx);
                else if (sc > kScore) beat.add(idx);
            }
        }

        boolean someoneReachedOrBeatKnocker = knockerValid && (!tied.isEmpty() || !beat.isEmpty());

        List<Integer> losers = new ArrayList<>();
        if (someoneReachedOrBeatKnocker) {
            losers.add(k);
        } else {
            int min = Integer.MAX_VALUE;
            for (int v : scoreByIdx.values()) min = Math.min(min, v);
            for (var e : scoreByIdx.entrySet()) {
                if (e.getValue() == min) losers.add(e.getKey());
            }
        }

        List<Integer> eliminatedNow = new ArrayList<>();
        for (int idx : losers) {
            Player p = s.getPlayers().get(idx);
            p.setLives(p.getLives() - 1);
            if (p.getLives() <= 0) {
                p.setEliminated(true);
                eliminatedNow.add(idx);
            }
        }

        if (countActivePlayers(s) <= 1) {
            Integer win = null;
            for (int i = 0; i < s.getPlayers().size(); i++) {
                if (!s.getPlayers().get(i).isEliminated()) { win = i; break; }
            }
            s.setWinnerIndex(win);
            s.setPhase(Phase.GAME_OVER);
            s.clearAllNotices();
            return;
        }

        String msg = buildKnockNoticeMessage(
                s, scoreByIdx,
                k, kScore, knockerValid,
                tied, beat,
                someoneReachedOrBeatKnocker,
                losers, eliminatedNow
        );
        for (int i = 0; i < s.getPlayers().size(); i++) {
            s.setNoticeForPlayer(i, msg);
        }

        s.setDealerIndex(nextActivePlayerIndex(s, s.getDealerIndex()));
        startRound(s);
    }

    private String buildKnockNoticeMessage(
            GameState s,
            Map<Integer, Integer> scoreByIdx,
            Integer knockerIdx,
            int knockerScore,
            boolean knockerValid,
            List<Integer> tied,
            List<Integer> beat,
            boolean someoneReachedOrBeatKnocker,
            List<Integer> losers,
            List<Integer> eliminatedNow
    ) {
        String kn = (knockerIdx != null && knockerIdx >= 0 && knockerIdx < s.getPlayers().size())
                ? safeName(s, knockerIdx)
                : "—";

        StringBuilder out = new StringBuilder();

        if (knockerValid) out.append("Bussata di ").append(kn).append(" (").append(knockerScore).append("). ");
        else out.append("Fine round dopo bussata. ");

        if (someoneReachedOrBeatKnocker && knockerValid) {
            if (!beat.isEmpty()) {
                out.append("Superato da: ").append(formatPlayersWithScores(s, beat, scoreByIdx)).append(". ");
            }
            if (!tied.isEmpty()) {
                out.append("Pareggiato da: ").append(formatPlayersWithScores(s, tied, scoreByIdx)).append(". ");
            }
            out.append(kn).append(" perde 1 vita.");
        } else {
            out.append("Punteggio più basso: ").append(formatPlayersWithScores(s, losers, scoreByIdx)).append(". ");
            out.append(losers.size() == 1 ? "Perde" : "Perdono").append(" 1 vita.");
        }

        if (!eliminatedNow.isEmpty()) {
            out.append(" Eliminato");
            out.append(eliminatedNow.size() == 1 ? ": " : "i: ");
            out.append(formatPlayersNames(s, eliminatedNow));
            out.append(".");
        }

        return out.toString();
    }

    private String safeName(GameState s, Integer idx) {
        if (idx == null || idx < 0 || idx >= s.getPlayers().size()) return "Player";
        Player p = s.getPlayers().get(idx);
        String n = (p != null ? p.getName() : null);
        if (n == null || n.isBlank()) return "Player " + (idx + 1);
        return n;
    }

    private String formatPlayersWithScores(GameState s, List<Integer> idxs, Map<Integer, Integer> scoreByIdx) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < idxs.size(); i++) {
            int idx = idxs.get(i);
            if (i > 0) sb.append(", ");
            sb.append(safeName(s, idx))
                    .append(" (")
                    .append(scoreByIdx.getOrDefault(idx, 0))
                    .append(")");
        }
        return sb.toString();
    }

    private String formatPlayersNames(GameState s, List<Integer> idxs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < idxs.size(); i++) {
            int idx = idxs.get(i);
            if (i > 0) sb.append(", ");
            sb.append(safeName(s, idx));
        }
        return sb.toString();
    }

    private int nextActivePlayerIndex(GameState s, int fromIndex) {
        int n = s.getPlayers().size();
        if (n <= 0) throw new IllegalStateException("Nessun giocatore nella partita.");

        for (int step = 1; step <= n; step++) {
            int i = (fromIndex + step) % n;
            if (!s.getPlayers().get(i).isEliminated()) return i;
        }
        throw new IllegalStateException("Nessun giocatore attivo.");
    }

    private int countActivePlayers(GameState s) {
        int c = 0;
        for (var p : s.getPlayers()) if (!p.isEliminated()) c++;
        return c;
    }

    private void refillDeckIfNeeded(GameState s, Random rng) {
        if (!s.getDeck().isEmpty()) return;
        if (s.getDiscard().size() <= 1) throw new IllegalStateException("Impossibile ricaricare il mazzo.");

        Card top = s.getDiscard().pop();
        List<Card> toShuffle = new ArrayList<>(s.getDiscard());
        s.getDiscard().clear();
        s.getDiscard().push(top);

        s.getDeck().addAllShuffled(toShuffle, rng);
    }
}
