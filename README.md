# Gioco del 31

Questo repository contiene un piccolo progetto personale realizzato per giocare ad un gioco con le carte napoletane denominato "31" con gli amici direttamente dal browser, tramite **stanze di gioco** e aggiornamenti in tempo reale.

## Panoramica
Il gioco permette di creare una stanza, invitare altri giocatori con un link/codice, e giocare una partita con regole semplici e turni sincronizzati tra tutti i partecipanti. L’interfaccia mostra lo stato della partita (turno corrente, vite, scarti, mazzo) e aggiorna automaticamente le informazioni per tutti i giocatori collegati.

## In cosa consiste il gioco 
L’obiettivo è ottenere una mano che totalizzi il valore più alto possibile **sullo stesso seme**, fino a un massimo di **31 punti**. Il gioco si basa su mazzo di carte napoletane, dove: 
- ogni figura ha valore 10;
- ogni asso ha valore 11;
- tutte le altre carte mantengono il proprio valore.

La partita si svolge nei seguenti passi che si ripetono:
- Ogni giocatore ha **3 carte in mano** e 3 vite totali.
- Nel proprio turno si **pesca una carta** (dal mazzo o dagli scarti) e si decide se **tenerla** scartando una delle tre carte in mano, oppure **scartarla** e chiudere il turno.
- In qualunque momento durante la partita è possibile **bussare** per avviare i turni finali: dopo la bussata, gli altri giocatori hanno un ultimo giro per migliorare la mano.
- A fine round, chi ha il punteggio peggiore (o chi ha bussato se qualcuno lo raggiunge/supera) **perde una vita**. I giocatori senza vite vengono eliminati.
- Vince la partita l’ultimo giocatore rimasto in gioco.

Se un giocatore raggiunge **31**, vince il round, facendo perdere una vita a tutti gli altri giocatori.

## Funzionalità
- Creazione di una stanza con **2–6 giocatori**
- Ingresso tramite **codice stanza** o **link di invito**
- Partita in tempo reale (stato sincronizzato per tutti i player)
- Gestione turni, pescata da mazzo/scarti, scarto carta, bussata
- Fine partita con vincitore e possibilità di **rigiocare**

## Stack / Tecnologie
- Java + Jakarta EE (Servlet)
- Jakarta WebSocket (`@ServerEndpoint`)
- JSP (pagine in `WEB-INF/jsp`)
- Frontend: HTML/CSS/JavaScript “vanilla”

## Requisiti
- **JDK 17+** (consigliato)
- **Apache Tomcat 10+** (Jakarta namespace `jakarta.*`)
- Browser moderno con supporto WebSocket

## Avvio in locale (Tomcat)
1. Clona il repository:
   ```bash
   git clone <URL_DEL_REPO>
   cd <NOME_CARTELLA>

