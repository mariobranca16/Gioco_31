/* Le funzioni pure su cui si appoggia tutto il resto del client. */

import { test } from "node:test";
import assert from "node:assert/strict";

import { installBrowserStubs, JS, card, player } from "./helpers.mjs";

installBrowserStubs();
const { cardImageUrl, cardKey, escapeHtml, hearts, isInPlay, isJoinedPlayer } =
    await import(`${JS}/util.js`);

test("i nomi dei giocatori finiscono nell'HTML senza poterlo alterare", () => {
    // I nomi arrivano da chi entra nella stanza e vengono inseriti come HTML
    // (anche dentro l'attributo title): è l'unico punto in cui un testo altrui
    // diventa markup.
    const nasty = `<img src=x onerror="alert(1)">`;
    const escaped = escapeHtml(nasty);

    assert.equal(escaped, "&lt;img src=x onerror=&quot;alert(1)&quot;&gt;");
    assert.ok(!escaped.includes("<"), "nessun tag può aprirsi");
    assert.ok(!escaped.includes('"'), "nessun attributo può chiudersi");

    assert.equal(escapeHtml(`d'Annunzio & C.`), "d&#39;Annunzio &amp; C.");
    assert.equal(escapeHtml(null), "", "un nome assente non diventa la stringa 'null'");
});

test("l'identità di una carta cambia con seme, valore e figura", () => {
    const asso = card("COPPE", "ASSO", 11);

    assert.equal(cardKey(asso), cardKey({ ...asso, label: "etichetta diversa" }),
        "l'etichetta non fa parte dell'identità");
    assert.notEqual(cardKey(asso), cardKey(card("SPADE", "ASSO", 11)));
    assert.notEqual(cardKey(asso), cardKey(card("COPPE", "TRE", 10)));
    assert.equal(cardKey(null), null);
});

test("l'immagine di una carta si ricava da seme e figura", () => {
    assert.equal(cardImageUrl(card("DENARI", "RE")), "/gioco31/images/denari_10.jpg");
    assert.equal(cardImageUrl(card("COPPE", "ASSO")), "/gioco31/images/coppe_1.jpg");
    assert.equal(cardImageUrl(card("QUADRI", "RE")), null, "seme non napoletano: nessuna immagine");
    assert.equal(cardImageUrl(null), null);
});

test("uno slot libero non è un giocatore seduto", () => {
    assert.equal(isJoinedPlayer(player(0, "Anna")), true);
    assert.equal(isJoinedPlayer(player(1, "Slot 2", { joined: false })), false);

    // Stati vecchi senza il campo joined: si ripiega sul nome dello slot.
    assert.equal(isJoinedPlayer({ name: "Slot 3" }), false);
    assert.equal(isJoinedPlayer({ name: "Anna" }), true);
    assert.equal(isJoinedPlayer({ name: "" }), false);
});

test("la partita è in corso solo mentre si gioca", () => {
    assert.equal(isInPlay("PLAYING"), true);
    assert.equal(isInPlay("KNOCK_CALLED"), true);
    assert.equal(isInPlay("WAITING_FOR_PLAYERS"), false);
    assert.equal(isInPlay("GAME_OVER"), false);
    assert.equal(isInPlay(undefined), false);
});

test("le vite si mostrano come cuori, mai in negativo", () => {
    assert.equal(hearts(3), "♥♥♥");
    assert.equal(hearts(0), "");
    assert.equal(hearts(-2), "");
});
