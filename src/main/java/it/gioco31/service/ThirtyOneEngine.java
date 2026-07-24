package it.gioco31.service;

import it.gioco31.GameConstants;
import it.gioco31.model.*;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public final class ThirtyOneEngine {

    /**
     * Generatore per mescolate e ricariche. SecureRandom (CSPRNG): il vecchio
     * new Random(seed + nanoTime) era un LCG a 48 bit, il cui stato interno si
     * ricostruisce osservando poche carte — quindi il resto del mazzo diventava
     * predicibile. SecureRandom non espone lo stato attraverso la sequenza
     * prodotta. Istanza unica e condivisa: è thread-safe e la creazione (con
     * relativo seeding) va fatta una sola volta.
     */
    private static final SecureRandom RNG = new SecureRandom();

    public void startRound(GameState s) {
        s.setWinnerIndex(null);

        s.setDeck(Deck.newNeapolitan40(RNG));
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
        armTurnTimer(s);
        s.addEvent("Nuovo round: tocca a " + safeName(s, s.getCurrentIndex()) + ".");
    }

    public void knock(GameState s) {
        ensurePhase(s, Phase.PLAYING);
        if (s.getPendingDraw() != null) throw new IllegalStateException("Prima devi decidere sulla carta pescata.");
        s.setKnockerIndex(s.getCurrentIndex());

        int remaining = Math.max(0, countActivePlayers(s) - 1);
        s.setFinalTurnsRemaining(remaining);
        s.setPhase(Phase.KNOCK_CALLED);

        String kn = safeName(s, s.getKnockerIndex());
        s.addEvent(kn + " ha bussato: ultimo giro per gli altri.");

        // Alert centrale per tutti: la bussata cambia il ritmo del round
        String noticeMsg = (remaining > 0)
                ? (kn + " ha bussato! Restano " + remaining + " turno/i finali.")
                : (kn + " ha bussato!");
        for (int i = 0; i < s.getPlayers().size(); i++) {
            if (i == s.getKnockerIndex()) continue;
            Player p = s.getPlayers().get(i);
            if (p.isEliminated() || p.isSpectating()) continue;
            s.setNoticeForPlayer(i, noticeMsg);
        }

        if (remaining <= 0) {
            resolveEndOfKnockRound(s);
            return;
        }

        s.setCurrentIndex(nextActivePlayerIndex(s, s.getCurrentIndex()));
        armTurnTimer(s);
    }

    public void drawPendingFromDeck(GameState s) {
        ensureActionAllowed(s);
        if (s.getPendingDraw() != null) throw new IllegalStateException("Hai già una carta pescata.");
        refillDeckIfNeeded(s, RNG);
        s.setPendingDraw(s.getDeck().draw());
        s.addEvent(safeName(s, s.getCurrentIndex()) + " pesca dal mazzo.");
    }

    public void drawPendingFromDiscard(GameState s) {
        ensureActionAllowed(s);
        if (s.getPendingDraw() != null) throw new IllegalStateException("Hai già una carta pescata.");
        if (s.getDiscard().isEmpty()) throw new IllegalStateException("Scarti vuoti");
        s.setPendingDraw(s.getDiscard().pop());
        s.addEvent(safeName(s, s.getCurrentIndex()) + " pesca " + s.getPendingDraw().label() + " dagli scarti.");
    }

    public boolean takeAndDiscard(GameState s, int handIndexToDiscard) {
        ensureActionAllowed(s);
        if (s.getPendingDraw() == null) throw new IllegalStateException("Nessuna carta pescata.");
        Player p = s.getPlayers().get(s.getCurrentIndex());
        if (handIndexToDiscard < 0 || handIndexToDiscard > 2) throw new IllegalArgumentException("Indice scarto errato.");

        Card old = p.getHand().set(handIndexToDiscard, s.getPendingDraw());
        s.getDiscard().push(old);
        s.setPendingDraw(null);
        s.addEvent(safeName(s, s.getCurrentIndex()) + " scarta " + old.label() + ".");

        if (!p.isEliminated() && p.hasThirtyOne()) return true;

        advanceTurn(s);
        return false;
    }

    /** Tiene la carta pescata scartando quella in mano; se completa un 31 applica la vittoria istantanea. */
    public void keepPendingDraw(GameState s, int handIndexToDiscard) {
        // da leggere prima che takeAndDiscard faccia avanzare il turno
        int playerIndex = s.getCurrentIndex();
        if (takeAndDiscard(s, handIndexToDiscard)) applyInstantThirtyOneWin(s, playerIndex);
    }

    public void rejectDraw(GameState s) {
        ensureActionAllowed(s);
        if (s.getPendingDraw() == null) throw new IllegalStateException("Nessuna carta pescata.");
        Card rejected = s.getPendingDraw();
        s.getDiscard().push(rejected);
        s.setPendingDraw(null);
        s.addEvent(safeName(s, s.getCurrentIndex()) + " scarta " + rejected.label() + " .");

        advanceTurn(s);
    }

    /**
     * Gioca d'ufficio il turno del giocatore corrente (timeout):
     * se non ha ancora pescato pesca dal mazzo, poi scarta la carta pescata.
     */
    public void forcePlayCurrentTurn(GameState s) {
        if (!s.getPhase().isInPlay()) return;
        if (s.getPendingDraw() == null) drawPendingFromDeck(s);
        rejectDraw(s);
    }

    /**
     * Rimuove un giocatore a partita in corso (leave o disconnessione):
     * scarta l'eventuale carta pescata, lo elimina, passa il turno,
     * riallinea i turni finali di bussata e dichiara il vincitore
     * se resta un solo giocatore attivo.
     */
    public void handlePlayerRemoved(GameState s, int removedIndex) {
        if (removedIndex < 0 || removedIndex >= s.getPlayers().size()) return;
        if (!s.getPhase().isInPlay()) return;

        Player removed = s.getPlayers().get(removedIndex);
        String removedName = safeName(s, removedIndex);

        if (s.getCurrentIndex() == removedIndex && s.getPendingDraw() != null) {
            s.getDiscard().push(s.getPendingDraw());
            s.setPendingDraw(null);
        }

        removed.getHand().clear();
        removed.setLives(0);
        removed.setEliminated(true);

        if (declareWinnerIfSingleActive(s)) return;

        s.addEvent(removedName + " ha lasciato la partita.");

        if (s.getCurrentIndex() == removedIndex) {
            s.setCurrentIndex(nextActivePlayerIndex(s, removedIndex));
            armTurnTimer(s);
        }

        if (s.getPhase() == Phase.KNOCK_CALLED) {
            int rem = pendingFinalTurns(s);
            s.setFinalTurnsRemaining(rem);
            if (rem <= 0) resolveEndOfKnockRound(s);
        }
    }

    /**
     * Applica la vittoria istantanea per 31: tutti gli altri giocatori attivi
     * perdono 1 vita; se resta un solo attivo la partita finisce, altrimenti
     * il dealer avanza e parte un nuovo round.
     */
    public void applyInstantThirtyOneWin(GameState s, int winnerIndex) {
        if (!s.getPhase().isInPlay()) return;

        s.setPendingDraw(null);

        // Snapshot di mani e punteggi prima di applicare le perdite
        Map<Integer, Integer> scoreByIdx = new LinkedHashMap<>();
        Map<Integer, List<Card>> handsByIdx = new LinkedHashMap<>();
        snapshotActiveHands(s, scoreByIdx, handsByIdx);

        List<Integer> losers = new ArrayList<>();
        List<Integer> eliminatedNow = new ArrayList<>();
        for (int i = 0; i < s.getPlayers().size(); i++) {
            if (i == winnerIndex) continue;

            Player other = s.getPlayers().get(i);
            if (other.isEliminated()) continue;

            losers.add(i);
            other.setLives(Math.max(0, other.getLives() - 1));
            if (other.getLives() <= 0) {
                other.setEliminated(true);
                other.getHand().clear();
                eliminatedNow.add(i);
            }
        }

        String msg = "31! " + safeName(s, winnerIndex)
                + " vince il round: tutti gli altri perdono 1 vita."
                + (eliminatedNow.isEmpty() ? "" : " (" + eliminatedNow.size() + " eliminato/i)");
        s.addEvent(msg);

        // prima dell'eventuale fine partita: le mani finali si vedono solo da qui
        s.setRoundResult(msg, buildRevealedHands(s, scoreByIdx, handsByIdx, losers, eliminatedNow));

        if (declareWinnerIfSingleActive(s)) return;

        s.setDealerIndex(nextActivePlayerIndex(s, s.getDealerIndex()));
        startRound(s);
    }

    /**
     * Se resta al più un giocatore attivo dichiara la fine della partita
     * (vincitore = unico attivo, o nessuno). Ritorna true se l'ha fatto.
     */
    private boolean declareWinnerIfSingleActive(GameState s) {
        if (countActivePlayers(s) > 1) return false;

        Integer win = s.firstActiveIndex();
        s.setWinnerIndex(win);
        s.setPhase(Phase.GAME_OVER);
        s.setTurnDeadlineMs(0);
        s.clearAllNotices();
        if (win != null) s.addEvent(safeName(s, win) + " vince la partita.");
        return true;
    }

    /**
     * Turni finali ancora da giocare dopo una bussata: i giocatori attivi
     * sull'arco che va dal giocatore corrente (incluso) al bussatore (escluso).
     */
    private int pendingFinalTurns(GameState s) {
        Integer k = s.getKnockerIndex();
        if (k == null) return 0;

        int n = s.getPlayers().size();
        int count = 0;
        for (int i = s.getCurrentIndex(); i != k; i = (i + 1) % n) {
            if (!s.getPlayers().get(i).isEliminated()) count++;
        }
        return count;
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

    /** Fotografa mani e punteggi dei giocatori non eliminati, prima di applicare le perdite. */
    private void snapshotActiveHands(GameState s, Map<Integer, Integer> scoreByIdx, Map<Integer, List<Card>> handsByIdx) {
        for (int i = 0; i < s.getPlayers().size(); i++) {
            Player p = s.getPlayers().get(i);
            if (!p.isEliminated()) {
                scoreByIdx.put(i, p.bestSameSuitScore().getValue());
                handsByIdx.put(i, new ArrayList<>(p.getHand()));
            }
        }
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
        armTurnTimer(s);
    }

    private void armTurnTimer(GameState s) {
        s.setTurnDeadlineMs(System.currentTimeMillis() + GameConstants.TURN_TIMEOUT_MS);
    }

    private void resolveEndOfKnockRound(GameState s) {
        Map<Integer, Integer> scoreByIdx = new LinkedHashMap<>();
        Map<Integer, List<Card>> handsByIdx = new LinkedHashMap<>();
        snapshotActiveHands(s, scoreByIdx, handsByIdx);

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

        String msg = buildKnockNoticeMessage(
                s, scoreByIdx,
                k, kScore, knockerValid,
                tied, beat,
                someoneReachedOrBeatKnocker,
                losers, eliminatedNow
        );
        s.addEvent(msg);

        // prima dell'eventuale fine partita: le mani finali si vedono solo da qui
        s.setRoundResult(msg, buildRevealedHands(s, scoreByIdx, handsByIdx, losers, eliminatedNow));

        if (declareWinnerIfSingleActive(s)) return;

        s.setDealerIndex(nextActivePlayerIndex(s, s.getDealerIndex()));
        startRound(s);
    }

    private List<GameState.RevealedHand> buildRevealedHands(
            GameState s,
            Map<Integer, Integer> scoreByIdx,
            Map<Integer, List<Card>> handsByIdx,
            List<Integer> losers,
            List<Integer> eliminatedNow
    ) {
        List<GameState.RevealedHand> out = new ArrayList<>();
        for (var e : handsByIdx.entrySet()) {
            int idx = e.getKey();
            out.add(new GameState.RevealedHand(
                    idx,
                    safeName(s, idx),
                    scoreByIdx.getOrDefault(idx, 0),
                    losers.contains(idx),
                    eliminatedNow.contains(idx),
                    e.getValue()
            ));
        }
        return out;
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
        Integer next = s.nextActiveIndexAfter(fromIndex);
        if (next == null) throw new IllegalStateException("Nessun giocatore attivo.");
        return next;
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
