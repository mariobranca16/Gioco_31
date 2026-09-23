/* Il contratto col server e il montaggio dei moduli. Sono le due cose che, se
   si rompono, si vedrebbero solo aprendo il browser: un import sbagliato o un
   frame in un formato che RoomEndpoint.parseRequest scarta in silenzio. */

import { test } from "node:test";
import assert from "node:assert/strict";

import { installBrowserStubs, JS } from "./helpers.mjs";

/** Socket finto che registra gli URL aperti e quel che il client spedisce. */
function recordingSocketClass(frames, opened) {
    return class {
        static OPEN = 1;
        static CONNECTING = 0;
        constructor(url){ this.url = url; this.readyState = 1; opened.push(url); }
        send(text){ frames.push(text); }
        close(){ this.readyState = 3; }
    };
}

const frames = [];
const opened = [];
installBrowserStubs({ ws: recordingSocketClass(frames, opened) });

const net = await import(`${JS}/net.js`);
const main = await import(`${JS}/main.js`);

test("le azioni partono come JSON, nel formato che il server sa leggere", () => {
    frames.length = 0;
    net.connect();

    net.sendAction("drawDeck");
    net.sendAction("keep", 2);
    net.sendAction("ackNotice", 7);
    net.sendAction("ping");

    assert.deepEqual(frames, [
        '{"action":"drawDeck"}',
        '{"action":"keep","arg":2}',
        '{"action":"ackNotice","arg":7}',
        '{"action":"ping"}'
    ]);

    // Ogni frame deve restare sotto il limite di lunghezza del server (200),
    // altrimenti verrebbe scartato prima di essere letto.
    for (const f of frames) assert.ok(f.length <= 200, "frame troppo lungo: " + f);
});

test("il socket punta alla stanza, sullo stesso host della pagina", () => {
    assert.equal(opened[0], "ws://localhost:8080/gioco31/ws/AB12");

    // Un secondo connect() su un socket già aperto non deve aprirne un altro:
    // è la guardia che evita di moltiplicare le connessioni a ogni
    // visibilitychange.
    const before = opened.length;
    net.connect();
    assert.equal(opened.length, before, "nessuna connessione doppia");
});

test("le funzioni chiamate dagli onclick della JSP esistono su window", () => {
    // room.jsp le invoca per nome: un modulo ha uno scope suo, quindi se il
    // ponte in main.js non le pubblicasse, i pulsanti non farebbero nulla.
    const fromJsp = [
        "ackNotice", "closeReveal", "confirmKeep", "copyInvite",
        "pileClick", "restartGame", "retryNow", "sendAction"
    ];

    for (const name of fromJsp) {
        assert.equal(typeof globalThis.window[name], "function", "manca su window: " + name);
    }
    assert.ok(main, "il modulo d'ingresso si carica");
});
