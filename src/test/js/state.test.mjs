/* Lo stato condiviso e la regola che protegge il tavolo dai messaggi arrivati
   fuori ordine: senza, un frame vecchio sovrascriverebbe uno più recente e la
   pagina tornerebbe indietro nel tempo. */

import { test } from "node:test";
import assert from "node:assert/strict";

import { installBrowserStubs, JS } from "./helpers.mjs";

installBrowserStubs();
const { acceptState, currentState, resetStateSeq } = await import(`${JS}/state.js`);

test("gli stati più recenti si applicano, quelli sorpassati no", () => {
    resetStateSeq();

    assert.equal(acceptState({ stateSeq: 5, phase: "PLAYING" }), true);
    assert.equal(currentState().phase, "PLAYING");

    assert.equal(acceptState({ stateSeq: 4, phase: "VECCHIO" }), false, "sorpassato");
    assert.equal(acceptState({ stateSeq: 5, phase: "DOPPIONE" }), false, "già applicato");
    assert.equal(currentState().phase, "PLAYING", "lo stato buono resta");

    assert.equal(acceptState({ stateSeq: 6, phase: "KNOCK_CALLED" }), true);
    assert.equal(currentState().phase, "KNOCK_CALLED");
});

test("dopo una riconnessione il primo stato va sempre applicato", () => {
    resetStateSeq();
    acceptState({ stateSeq: 99, phase: "PLAYING" });

    // Il server riparte dalla sua sequenza, che può essere più bassa: se il
    // client continuasse a confrontare con la vecchia, non si aggiornerebbe più.
    resetStateSeq();
    assert.equal(acceptState({ stateSeq: 1, phase: "WAITING_FOR_PLAYERS" }), true);
    assert.equal(currentState().phase, "WAITING_FOR_PLAYERS");
});

test("un messaggio senza numero di sequenza viene comunque applicato", () => {
    resetStateSeq();
    assert.equal(acceptState({ phase: "PLAYING" }), true);
    assert.equal(currentState().phase, "PLAYING");
});
