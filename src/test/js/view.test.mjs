/* Le decisioni dell'interfaccia: chi può pescare, cosa dice il banner, quando
   il pulsante di avvio è attivo. Prima stavano dentro render(), mescolate alle
   scritture sul DOM, e non erano verificabili senza un browser. */

import { test } from "node:test";
import assert from "node:assert/strict";

import { installBrowserStubs, JS, card, player, stateWith } from "./helpers.mjs";

installBrowserStubs();
const { computeView } = await import(`${JS}/view.js`);

test("in lobby: banner d'attesa, nessuna mossa possibile", () => {
    const view = computeView(stateWith({ phase: "WAITING_FOR_PLAYERS", turnSecondsLeft: null }));

    assert.equal(view.banner.pillText, "IN ATTESA");
    assert.equal(view.banner.pillTurn, false);
    assert.equal(view.buttons.drawDeck, false);
    assert.equal(view.buttons.drawDiscard, false);
    assert.equal(view.buttons.knock, false);
    assert.equal(view.tableInPlay, false);
    assert.equal(view.turnSecondsLeft, null);
});

test("l'avvio è dell'host, e solo con abbastanza giocatori", () => {
    const lobby = { phase: "WAITING_FOR_PLAYERS" };

    const hostReady = computeView(stateWith({ ...lobby, viewerIsHost: true }));
    assert.equal(hostReady.start.visible, true);
    assert.equal(hostReady.start.enabled, true);
    assert.match(hostReady.start.hint, /Pronto/);

    const notHost = computeView(stateWith({ ...lobby, viewerIsHost: false }));
    assert.equal(notHost.start.enabled, false);
    assert.match(notHost.start.hint, /Solo il creatore/);

    // Il secondo slot è ancora libero: "Slot 2" non è un giocatore seduto.
    const alone = computeView(stateWith({
        ...lobby,
        viewerIsHost: true,
        players: [player(0, "Anna"), player(1, "Slot 2", { joined: false })]
    }));
    assert.equal(alone.start.enabled, false);
    assert.match(alone.start.hint, /almeno 2/);
});

test("a partita avviata il pulsante di avvio sparisce, senza suggerimenti", () => {
    const view = computeView(stateWith({ phase: "PLAYING" }));
    assert.equal(view.start.visible, false);
    assert.equal(view.start.hint, "");
});

test("è il mio turno: posso pescare e bussare", () => {
    const view = computeView(stateWith({ currentIndex: 0, viewerIndex: 0 }));

    assert.equal(view.banner.pillText, "TOCCA A TE");
    assert.equal(view.banner.pillTurn, true);
    assert.equal(view.buttons.drawDeck, true);
    assert.equal(view.buttons.drawDiscard, true);
    assert.equal(view.buttons.knock, true);
});

test("turno di un altro: nessun comando attivo, e il banner dice chi gioca", () => {
    const view = computeView(stateWith({ currentIndex: 1, viewerIndex: 0 }));

    assert.equal(view.banner.pillText, "Turno di Bruno");
    assert.equal(view.banner.pillTurn, false);
    assert.equal(view.buttons.drawDeck, false);
    assert.equal(view.buttons.drawDiscard, false);
    assert.equal(view.buttons.knock, false);
    assert.equal(view.hand.allowSwap, false);
});

test("scarti vuoti: si pesca solo dal mazzo", () => {
    const view = computeView(stateWith({ discardTop: null }));

    assert.equal(view.buttons.drawDeck, true);
    assert.equal(view.buttons.drawDiscard, false);
    assert.equal(view.discardKey, "");
});

test("con una carta già pescata si sceglie cosa tenere, non si pesca ancora", () => {
    const pending = card("BASTONI", "FANTE");
    const view = computeView(stateWith({
        viewPending: pending,
        players: [player(0, "Anna", { pendingDraw: pending }), player(1, "Bruno")]
    }));

    assert.equal(view.buttons.drawDeck, false, "non si pesca due volte");
    assert.equal(view.buttons.drawDiscard, false);
    assert.equal(view.buttons.knock, false, "prima si chiude la pescata");
    assert.equal(view.hand.allowSwap, true, "la mano diventa selezionabile");
    assert.equal(view.pending.show, true);
    assert.deepEqual(view.pending.card, pending);
});

test("dopo la bussata non si bussa di nuovo, ma si pesca ancora", () => {
    const view = computeView(stateWith({ phase: "KNOCK_CALLED" }));

    assert.equal(view.buttons.knock, false);
    assert.equal(view.buttons.drawDeck, true);
    assert.match(view.banner.sub, /Turni finali/);
});

test("chi è eliminato guarda la mano di chi sta giocando", () => {
    const view = computeView(stateWith({
        viewerIndex: 0,
        currentIndex: 1,
        viewerEliminated: true,
        handViewIndex: 1,
        players: [player(0, "Anna", { eliminated: true, lives: 0 }), player(1, "Bruno")]
    }));

    assert.match(view.handTitle, /Mano di Bruno/);
    assert.equal(view.info.name, "Bruno", "i dati mostrati sono di chi gioca");
    assert.equal(view.buttons.drawDeck, false, "un eliminato non muove nulla");
});

test("lo spettatore entrato a partita in corso è avvisato che aspetta il prossimo giro", () => {
    const view = computeView(stateWith({
        viewerIndex: 1,
        currentIndex: 0,
        viewerSpectating: true,
        viewerEliminated: true,
        handViewIndex: 0
    }));

    assert.equal(view.banner.pillText, "SPETTATORE");
    assert.match(view.banner.sub, /prossima partita/);
    assert.match(view.handTitle, /stai guardando/);
});

test("a partita finita compare il vincitore e l'avviso viene messo da parte", () => {
    const view = computeView(stateWith({
        phase: "GAME_OVER",
        winnerIndex: 1,
        turnSecondsLeft: null,
        players: [
            player(0, "Anna", { noticeId: 7, noticeMsg: "Mossa non valida" }),
            player(1, "Bruno")
        ]
    }));

    assert.deepEqual(view.winner, { name: "Bruno", isHost: true });
    assert.equal(view.notice, null, "un solo overlay alla volta: vince il vincitore");
    assert.equal(view.tableInPlay, true, "il tavolo resta nella forma di gioco");
    assert.equal(view.phaseText, "Partita terminata");
});

test("l'avviso per il giocatore arriva alla vista solo se ha id e testo", () => {
    const withNotice = computeView(stateWith({
        players: [player(0, "Anna", { noticeId: 7, noticeMsg: "Scarti vuoti" }), player(1, "Bruno")]
    }));
    assert.deepEqual(withNotice.notice, { id: 7, msg: "Scarti vuoti" });

    const empty = computeView(stateWith({
        players: [player(0, "Anna", { noticeId: 7, noticeMsg: "" }), player(1, "Bruno")]
    }));
    assert.equal(empty.notice, null);
});

test("uno stato incompleto non fa saltare la vista", () => {
    // Il client non deve fidarsi della forma del messaggio: un campo mancante
    // deve produrre una vista neutra, non un errore che blocca il render.
    const view = computeView({});

    assert.equal(view.players.length, 0);
    assert.equal(view.turnText, "—");
    assert.equal(view.info.name, "—");
    assert.equal(view.bestSuitText, "—");
    assert.equal(view.winner, null);
    assert.equal(view.notice, null);
    assert.deepEqual(view.hand.cards, []);
});
