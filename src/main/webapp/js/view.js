/* Dallo stato del server a "cosa deve mostrare la pagina", senza toccare il
   DOM. È qui che stanno tutte le decisioni — di chi è il turno, quali pulsanti
   sono attivi, che testo va nel banner, se la mano è scambiabile — così si
   possono verificare senza un browser: render.js si limita a scrivere a
   schermo quello che questo modulo ha deciso.

   Nessuno stato: la stessa istantanea produce sempre la stessa vista. Le
   decisioni che dipendono da *com'era prima* (animazioni, esito di round già
   mostrato) restano in render.js, che quella memoria ce l'ha. */

import { cardKey, isInPlay, isJoinedPlayer } from "./util.js";

const PHASE_LABELS = {
    "WAITING_FOR_PLAYERS": "In attesa di giocatori",
    "PLAYING": "In gioco",
    "KNOCK_CALLED": "Bussata — ultimi turni",
    "GAME_OVER": "Partita terminata"
};

function phaseLabel(phase){
    return PHASE_LABELS[phase] ?? phase;
}

/** Il numero minimo di giocatori seduti perché l'host possa avviare. */
const MIN_PLAYERS_TO_START = 2;

export function computeView(state){
    const phase = state.phase ?? "—";
    const viewerIndex = state.viewerIndex ?? -1;
    const currentIndex = state.currentIndex ?? -1;
    const winnerIndex = state.winnerIndex;
    const players = Array.isArray(state.players) ? state.players : [];

    const viewer = playerAt(players, viewerIndex);
    const inPlay = isInPlay(phase);
    const viewerSpectating = !!state.viewerSpectating;

    // "spectator" è chi è fuori dal giro (eliminato o entrato a partita in
    // corso): vede la mano di chi sta giocando, non la propria.
    const spectator = !!state.viewerEliminated;
    const myTurn = (!spectator && viewerIndex === currentIndex);

    const handViewIndex = handOwnerIndex(state, players, viewerIndex);
    const handOwner = playerAt(players, handViewIndex);
    const infoOwner = spectator ? handOwner : viewer;

    const viewPending = state.viewPending ?? null;
    const hasPendingSelf = !!(viewer && viewer.pendingDraw);
    const allowSwap = !!(myTurn && inPlay && viewPending);

    const canDrawDeck = (myTurn && inPlay && !hasPendingSelf);
    const canDrawDiscard = (canDrawDeck && !!state.discardTop);
    const canKnock = (myTurn && phase === "PLAYING" && !hasPendingSelf);

    const gameOver = (phase === "GAME_OVER" && playerAt(players, winnerIndex) != null);

    return {
        phase,
        viewerIndex,
        currentIndex,
        players,

        // La riga in alto cambia aspetto anche a partita finita, non solo in gioco.
        tableInPlay: (inPlay || phase === "GAME_OVER"),

        phaseText: phaseLabel(phase),
        turnText: (currentIndex >= 0 ? String(currentIndex + 1) : "—"),

        deckSize: (state.deckSize ?? 0),
        discardTop: state.discardTop ?? null,
        discardKey: cardKey(state.discardTop) ?? "",

        banner: banner(phase, viewerIndex, currentIndex, players, viewerSpectating),

        info: {
            name: infoOwner?.name ?? "—",
            lives: (infoOwner && infoOwner.lives != null) ? String(infoOwner.lives) : "—"
        },
        handTitle: handTitle(viewerSpectating, spectator, handOwner),

        hand: {
            cards: Array.isArray(state.viewHand) ? state.viewHand : [],
            allowSwap
        },
        bestSuitText: bestSuitText(state),

        pending: {
            show: !!(inPlay && viewPending),
            card: viewPending
        },

        buttons: { drawDeck: canDrawDeck, drawDiscard: canDrawDiscard, knock: canKnock },
        start: startButton(phase, players, !!state.viewerIsHost),

        // Vincitore e avviso si escludono: a partita finita l'avviso sparisce.
        winner: gameOver
            ? { name: playerName(players, winnerIndex), isHost: !!state.viewerIsHost }
            : null,
        notice: gameOver ? null : notice(viewer),

        events: Array.isArray(state.events) ? state.events : [],
        roundResult: state.roundResult ?? null,
        turnSecondsLeft: (typeof state.turnSecondsLeft === "number") ? state.turnSecondsLeft : null,

        // Istantanea ridotta con cui render.js decide le animazioni di pesca.
        animSnapshot: {
            phase: state.phase,
            currentIndex: state.currentIndex,
            deckSize: state.deckSize,
            discardTop: state.discardTop
        }
    };
}

function playerAt(players, index){
    if (index == null || index < 0 || index >= players.length) return null;
    return players[index];
}

function playerName(players, index){
    return playerAt(players, index)?.name ?? ("Player " + (index + 1));
}

/* Chi è fuori dal giro guarda la mano di chi sta giocando: senza, resterebbe
   davanti a tre caselle vuote per tutta la partita. */
function handOwnerIndex(state, players, viewerIndex){
    let idx = (state.handViewIndex ?? viewerIndex);
    if (idx < 0 || idx >= players.length) idx = viewerIndex;
    if (idx < 0 || idx >= players.length) idx = 0;
    return idx;
}

function handTitle(viewerSpectating, spectator, handOwner){
    const name = handOwner?.name ?? "—";
    if (viewerSpectating) return "Mano di " + name + " (stai guardando)";
    if (spectator) return "Mano di " + name + " (spettatore)";
    return "La tua mano";
}

function bestSuitText(state){
    const lbl = state.viewBestSuitLabel;
    const val = state.viewBestSuitValue;
    if (lbl != null && val !== undefined && val !== null) return `${lbl} — ${val} punti`;
    return "—";
}

function notice(viewer){
    const id = viewer?.noticeId;
    const msg = viewer?.noticeMsg;
    if (id == null || typeof msg !== "string" || msg.length === 0) return null;
    return { id, msg };
}

function startButton(phase, players, isHost){
    const waiting = (phase === "WAITING_FOR_PLAYERS");
    const joined = players.reduce((acc, p) => acc + (isJoinedPlayer(p) ? 1 : 0), 0);

    let hint = "";
    if (waiting) {
        if (!isHost) hint = "Solo il creatore della stanza può avviare la partita.";
        else if (joined < MIN_PLAYERS_TO_START) hint = "Servono almeno " + MIN_PLAYERS_TO_START + " giocatori per iniziare.";
        else hint = "Pronto: puoi avviare la partita.";
    }

    return {
        visible: waiting,
        enabled: (waiting && isHost && joined >= MIN_PLAYERS_TO_START),
        hint
    };
}

function banner(phase, viewerIndex, currentIndex, players, viewerSpectating){
    const curName = (currentIndex >= 0 && currentIndex < players.length)
        ? (players[currentIndex]?.name ?? ("Player " + (currentIndex + 1)))
        : "—";

    if (phase === "WAITING_FOR_PLAYERS") {
        return {
            title: "In attesa di giocatori",
            sub: "Quando siete pronti, il creatore della stanza può avviare la partita.",
            pillText: "IN ATTESA",
            pillTurn: false
        };
    }
    if (phase === "GAME_OVER") {
        return {
            title: "Partita terminata",
            sub: "È stato dichiarato un vincitore.",
            pillText: "FINE PARTITA",
            pillTurn: false
        };
    }
    if (viewerSpectating) {
        return {
            title: "Sei uno spettatore",
            sub: "Stai guardando la partita in corso. Entrerai a giocare nella prossima partita.",
            pillText: "SPETTATORE",
            pillTurn: false
        };
    }
    if (viewerIndex === currentIndex) {
        return {
            title: "È il tuo turno",
            sub: (phase === "KNOCK_CALLED")
                ? "Turni finali dopo bussata: pesca e chiudi il turno."
                : "Pesca una carta o bussa (se vuoi chiudere).",
            pillText: "TOCCA A TE",
            pillTurn: true
        };
    }
    return {
        title: "In attesa",
        sub: "Sta giocando: " + curName,
        pillText: "Turno di " + curName,
        pillTurn: false
    };
}
