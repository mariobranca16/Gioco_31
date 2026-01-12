# Gioco del 31

Questo repository contiene un **progetto web multiplayer** realizzato a scopo personale per giocare al tradizionale gioco di carte napoletane **31** direttamente dal browser.  
Il gioco supporta **stanze di gioco**, più giocatori simultanei e **aggiornamenti in tempo reale**.

---

## Panoramica

L’applicazione consente di:

- creare una **stanza di gioco**
- invitare altri giocatori tramite **codice stanza** o **link**
- giocare una partita completa con **turni sincronizzati** tra tutti i partecipanti

L’interfaccia mostra in tempo reale lo **stato della partita**, inclusi:
- turno corrente  
- carte in mano  
- vite rimanenti  
- mazzo e pila degli scarti  

Tutti i client collegati ricevono aggiornamenti automatici grazie all’uso dei **WebSocket**.

---

## Regole del gioco

L’obiettivo del gioco è ottenere una mano che totalizzi il **valore più alto possibile sullo stesso seme**, fino a un massimo di **31 punti**.

Il gioco utilizza un **mazzo di carte napoletane**, con i seguenti valori:

- **Figure** → valore 10  
- **Asso** → valore 11  
- **Carte numeriche** → mantengono il proprio valore  

---

### Svolgimento della partita

La partita si articola in round che si ripetono secondo queste regole:

- Ogni giocatore ha **3 carte in mano** e **3 vite** iniziali
- Nel proprio turno un giocatore:
  - pesca una carta dal **mazzo** o dagli **scarti**
  - può **tenerla**, scartando una delle carte in mano  
  - oppure **scartarla immediatamente**, chiudendo il turno
- In qualsiasi momento è possibile **bussare**, avviando il giro finale:
  - dopo la bussata, tutti gli altri giocatori hanno **un ultimo turno**
- Al termine del round:
  - il giocatore con il **punteggio più basso** perde una vita  
  - chi perde tutte le vite viene eliminato
- Vince la partita **l’ultimo giocatore rimasto in gioco**

**Regola speciale**  
Se un giocatore raggiunge **31 punti**, vince immediatamente il round e **tutti gli altri giocatori perdono una vita**.

---

## Funzionalità principali

- Creazione di stanze con **2–6 giocatori**
- Accesso tramite **codice stanza** o **link di invito**
- Partita **multiplayer in tempo reale**
- Gestione completa di:
  - turni
  - pescata (mazzo o scarti)
  - scarto carte
  - bussata
- Gestione eliminazioni e **vincitore finale**
- Possibilità di **rigiocare** al termine della partita

---

## Tecnologie utilizzate

- **Java 17**
- **Jakarta EE**
  - Servlet
  - WebSocket (`@ServerEndpoint`)
- **JSP** (collocate in `WEB-INF/jsp`)
- **Frontend**: HTML, CSS, JavaScript (vanilla)
- **Apache Tomcat 10+**

---

## Requisiti

- **JDK 17 o superiore** (consigliato)
- **Apache Tomcat 10+**  
  > Necessario per il namespace `jakarta.*`
- Browser moderno con supporto **WebSocket**

---

## Avvio in locale (Tomcat)

1. Clona il repository:
   ```bash
   git clone <URL_DEL_REPOSITORY>
   cd <NOME_CARTELLA>
2. Importa il progetto nel tuo IDE (es. IntelliJ IDEA)
3. Assicurati che il progetto sia configurato come Web Application e utilizzi Jakarta EE (namespace jakarta.*)
4. Configura Apache Tomcat 10+ come Application Server
5. Avvia il server
6. Apri il browser e accedi a: http://localhost:8080/(context-path del progetto)

## Note

- Il progetto è stato realizzato a scopo didattico e sperimentale
- Non utilizza framework frontend: tutta la logica client è scritta in JavaScript “vanilla”
- La sincronizzazione dello stato di gioco avviene esclusivamente tramite WebSocket
- Le JSP sono collocate in WEB-INF e non sono accessibili direttamente dal browser
