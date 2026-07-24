# Gioco del 31

Gioco di carte napoletane multiplayer, giocabile direttamente dal browser, realizzato come progetto personale per consolidare lo sviluppo web con Java e Jakarta EE.

Il gioco supporta stanze con più partecipanti, partite con più giocatori simultanei e aggiornamenti in tempo reale grazie ai WebSocket. Ogni client collegato riceve in automatico lo stato aggiornato della partita: turno corrente, carte in mano, vite rimanenti, mazzo e pila degli scarti.

## Tecnologie

- Java con Jakarta EE (Servlet e WebSocket tramite `@ServerEndpoint`)
- JSP per le viste (collocate in `WEB-INF/jsp`)
- HTML, CSS e JavaScript vanilla per il frontend, senza framework
- Apache Tomcat come server

## Regole del gioco

L'obiettivo è ottenere una mano che totalizzi il valore più alto possibile sullo stesso seme, fino a un massimo di 31 punti. Si gioca con un mazzo di carte napoletane: le figure valgono 10, l'asso vale 11 e le carte numeriche mantengono il proprio valore.

Ogni giocatore parte con 3 carte in mano e 3 vite. Nel proprio turno può pescare una carta dal mazzo o dagli scarti e poi tenerla, scartando una carta della mano, oppure scartarla subito chiudendo il turno. In qualsiasi momento è possibile bussare: da quel momento tutti gli altri giocatori hanno un ultimo turno.

Al termine di ogni round, il giocatore con il punteggio più basso perde una vita, e chi resta senza vite viene eliminato. Vince la partita l'ultimo giocatore rimasto in gioco. Come regola speciale, chi raggiunge esattamente 31 punti vince subito il round e fa perdere una vita a tutti gli altri.

## Funzionalità

- creazione di stanze da 2 a 6 giocatori
- accesso tramite codice stanza o link di invito
- partita multiplayer in tempo reale
- gestione completa di turni, pescata, scarto e bussata
- gestione delle eliminazioni e del vincitore finale
- possibilità di rigiocare al termine della partita

## Robustezza e rete

- **Disconnessioni.** Chi perde la connessione o chiude la scheda ha qualche
  secondo per rientrare prima di lasciare il posto al tavolo; le stanze rimaste
  vuote vengono chiuse da sole.
- **Turni bloccati.** Se un giocatore non gioca entro il tempo massimo, il suo
  turno viene giocato in automatico, così la partita va avanti lo stesso.
- **Riconnessione automatica.** Il client si ricollega da solo, subito quando
  riapri la scheda o torna la rete.
- **Aggiornamenti in ordine.** Ogni aggiornamento ha un numero progressivo: quelli
  che arrivano in ritardo vengono ignorati, così non si torna mai indietro.
- **Protezioni.** I messaggi anomali vengono scartati senza far cadere la
  connessione, e le carte vengono mescolate in modo davvero casuale.

I tempi e le soglie sono raccolti in `it.gioco31.GameConstants`.

## Come avviare il gioco

Prerequisiti: JDK 17 o superiore, Apache Tomcat 10 o superiore (necessario per il namespace `jakarta.*`) e un browser con supporto ai WebSocket.

1. Clonare il repository:

   ```bash
   git clone https://github.com/mariobranca16/Gioco_31.git
   ```

2. Avviare senza IDE, direttamente da riga di comando (scarica Tomcat in automatico):

   ```bash
   ./mvnw package cargo:run
   ```

   poi aprire `http://localhost:8080/gioco31`.

   In alternativa, importare il progetto nell'IDE (ad esempio IntelliJ IDEA) come web application Jakarta EE, configurare Apache Tomcat e avviarlo.

3. Per eseguire i test:

   ```bash
   ./mvnw test
   ```

## Note

Il progetto nasce come esercizio didattico e sperimentale, sviluppato per divertimento e per approfondire lo sviluppo web con Java e Jakarta EE. Tutta la logica del client è scritta in JavaScript vanilla e la sincronizzazione dello stato di gioco avviene esclusivamente tramite WebSocket.
