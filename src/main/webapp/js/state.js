/* L'ultimo stato ricevuto dal server: l'unico dato condiviso da più moduli.
   Tutto il resto (socket e timer in net.js, selezione della mano in render.js,
   avvisi in ui.js) resta privato di chi lo gestisce. */

let lastState = null;
let lastAppliedSeq = -1;

/** Lo stato su cui si sta lavorando, o null se non ne è ancora arrivato uno. */
export function currentState(){
    return lastState;
}

/* Nuova connessione: la sequenza del server riparte dal nostro punto di vista,
   quindi il primo stato che arriverà va sempre applicato. */
export function resetStateSeq(){
    lastAppliedSeq = -1;
}

/**
 * Registra uno stato appena arrivato. Torna false se è più vecchio di quello
 * già applicato: i frame fuori ordine vanno scartati, non renderizzati.
 */
export function acceptState(state){
    const seq = state?.stateSeq;
    if (typeof seq === "number") {
        if (seq <= lastAppliedSeq) return false;
        lastAppliedSeq = seq;
    }
    lastState = state;
    return true;
}
