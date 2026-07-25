package it.gioco31.controller;

import it.gioco31.GameConstants;
import it.gioco31.model.Player;
import it.gioco31.room.GameRoom;
import it.gioco31.room.RoomRepository;
import it.gioco31.util.UrlUtil;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;

import java.io.IOException;
import java.util.UUID;

@WebServlet("/join")
public class JoinRoomServlet extends HttpServlet {

    private static int parseClamped(String raw, int def, int min, int max) {
        int v = def;
        if (raw != null) {
            try { v = Integer.parseInt(raw.trim()); } catch (NumberFormatException ignored) {}
        }
        return Math.max(min, Math.min(max, v));
    }

    private static void forwardJoinWithError(HttpServletRequest req, HttpServletResponse resp, String msg)
            throws ServletException, IOException {
        req.setAttribute("error", msg);
        req.getRequestDispatcher("/WEB-INF/jsp/join.jsp").forward(req, resp);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        req.getRequestDispatcher("/WEB-INF/jsp/join.jsp").forward(req, resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {

        String action = req.getParameter("action");
        if (action == null || action.isBlank()) action = "join";

        String nameRaw = req.getParameter("name");
        if (nameRaw == null || nameRaw.isBlank()) {
            forwardJoinWithError(req, resp, "Inserisci un nome valido.");
            return;
        }

        HttpSession session = req.getSession(true);

        String roomId;
        GameRoom room;

        if ("create".equalsIgnoreCase(action)) {
            int slots = parseClamped(req.getParameter("players"), 4,
                    GameConstants.MIN_PLAYERS, GameConstants.MAX_PLAYERS);
            int lives = parseClamped(req.getParameter("lives"), GameConstants.DEFAULT_LIVES,
                    GameConstants.MIN_LIVES, GameConstants.MAX_LIVES);
            room = RoomRepository.createNewRoom(slots, lives);
            roomId = RoomRepository.normalizeRoomId(room.roomId());

        } else if ("join".equalsIgnoreCase(action)) {

            roomId = RoomRepository.normalizeRoomId(req.getParameter("room"));
            if (!isValidRoomId(roomId)) {
                forwardJoinWithError(req, resp, "Codice stanza non valido.");
                return;
            }

            room = RoomRepository.get(roomId);
            if (room == null) {
                forwardJoinWithError(req, resp, "Room non trovata.");
                return;
            }

        } else {
            forwardJoinWithError(req, resp, "Azione non valida.");
            return;
        }

        String existingToken = (String) session.getAttribute("playerToken");
        String existingRoom = RoomRepository.normalizeRoomId((String) session.getAttribute("roomId"));

        if (existingToken != null && roomId.equals(existingRoom)) {
            Integer idx = room.indexByToken(existingToken);
            if (idx != null) {
                resp.sendRedirect(req.getContextPath() + "/room?room=" + UrlUtil.enc(roomId));
                return;
            } else {
                session.removeAttribute("playerToken");
                session.removeAttribute("roomId");
            }
        }

        String token = UUID.randomUUID().toString();

        // Il forward alla JSP va fatto FUORI dal lock della stanza:
        // qui si raccoglie solo l'esito.
        String joinError = tryJoin(room, nameRaw.trim(), token);
        if (joinError != null) {
            forwardJoinWithError(req, resp, joinError);
            return;
        }

        session.setAttribute("playerToken", token);
        session.setAttribute("roomId", roomId);

        resp.sendRedirect(req.getContextPath() + "/room?room=" + UrlUtil.enc(roomId));
    }

    /**
     * Occupa uno slot per il giocatore. Ritorna null se ok, altrimenti il
     * messaggio d'errore. Package-private per essere testato direttamente:
     * è il punto in cui più richieste concorrenti si contendono gli slot.
     */
    static String tryJoin(GameRoom room, String candidate, String token) {
        room.lock().lock();
        try {
            for (Player p : room.state().getPlayers()) {
                if (p.isJoined() && p.getName() != null && p.getName().equalsIgnoreCase(candidate)) {
                    return "Nome già utilizzato nella room.";
                }
            }

            int idx = findFreeSlot(room);
            if (idx < 0) return "Room piena.";

            Player me = room.state().getPlayers().get(idx);
            try {
                me.setName(candidate);
            } catch (IllegalArgumentException ex) {
                return ex.getMessage();
            }

            boolean inProgress = room.state().getPhase().isInPlay();

            me.setJoined(true);
            if (inProgress) {
                // Entra come spettatore: parteciperà dalla prossima partita
                me.setEliminated(true);
                me.setSpectating(true);
                me.setLives(0);
            } else {
                me.setEliminated(false);
                me.setSpectating(false);
            }
            room.bindToken(token, idx);
            room.state().addEvent(candidate
                    + (inProgress ? " guarda la partita: entrerà dalla prossima." : " si è seduto al tavolo."));
            return null;
        } finally {
            room.lock().unlock();
        }
    }

    private static int findFreeSlot(GameRoom room) {
        for (int i = 0; i < room.state().getPlayers().size(); i++) {
            Player p = room.state().getPlayers().get(i);
            if (!p.isJoined()) return i;
        }
        return -1;
    }

    private static boolean isValidRoomId(String roomId) {
        String id = RoomRepository.normalizeRoomId(roomId);
        if (id == null) return false;
        int len = id.length();
        if (len < GameConstants.ROOM_ID_MIN_LEN || len > GameConstants.ROOM_ID_MAX_LEN) return false;
        return id.matches("[A-Z0-9]+");
    }
}
