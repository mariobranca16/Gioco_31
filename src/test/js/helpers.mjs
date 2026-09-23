/* Aiuti comuni ai test del client. */

export const JS = "../../main/webapp/js";

/**
 * Stub minimi di browser. Servono ai moduli che al caricamento leggono
 * window (config.js) o registrano listener (main.js): non simulano un DOM,
 * perché i test qui verificano decisioni, non pixel.
 */
export function installBrowserStubs({ ws } = {}) {
    const noop = () => {};
    globalThis.window = {
        __ROOM_ID__: "AB12",
        __CTX__: "/gioco31",
        addEventListener: noop,
        isSecureContext: false
    };
    globalThis.document = {
        addEventListener: noop,
        getElementById: () => null,
        querySelector: () => null,
        querySelectorAll: () => []
    };
    globalThis.location = {
        origin: "http://localhost:8080",
        protocol: "http:",
        host: "localhost:8080"
    };
    globalThis.WebSocket = ws ?? class {
        static OPEN = 1;
        static CONNECTING = 0;
        constructor(url){ this.url = url; this.readyState = 1; }
        send(){}
        close(){}
    };
}

/** Stato del server con i campi che il client si aspetta, più le modifiche del caso. */
export function stateWith(overrides = {}) {
    const base = {
        stateSeq: 1,
        phase: "PLAYING",
        viewerIndex: 0,
        currentIndex: 0,
        winnerIndex: null,
        deckSize: 30,
        discardTop: card("DENARI", "RE"),
        viewerEliminated: false,
        viewerSpectating: false,
        viewerIsHost: true,
        handViewIndex: 0,
        turnSecondsLeft: 60,
        events: [],
        viewHand: [card("COPPE", "ASSO"), card("COPPE", "TRE"), card("SPADE", "SETTE")],
        viewBestSuitValue: 14,
        viewBestSuitLabel: "Coppe",
        viewPending: null,
        players: [player(0, "Anna"), player(1, "Bruno")]
    };
    return { ...base, ...overrides };
}

export function player(index, name, overrides = {}) {
    return {
        index,
        name,
        lives: 3,
        eliminated: false,
        spectating: false,
        joined: true,
        cardCount: 3,
        pendingDraw: null,
        noticeId: null,
        noticeMsg: null,
        ...overrides
    };
}

export function card(suit, rank, value = 10) {
    return { suit, rank, value, label: `${rank} di ${suit}` };
}
