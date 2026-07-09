package it.gioco31.controller;

import it.gioco31.model.Phase;
import it.gioco31.room.GameRoom;
import it.gioco31.room.RoomRepository;
import it.gioco31.service.GameLifecycle;
import it.gioco31.util.UrlUtil;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;

import java.io.IOException;

@WebServlet("/start")
public class StartGameServlet extends HttpServlet {

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {

        HttpSession session = req.getSession(false);
        if (session == null) {
            resp.sendRedirect(req.getContextPath() + "/join");
            return;
        }

        String roomId = RoomRepository.normalizeRoomId((String) session.getAttribute("roomId"));
        String token = (String) session.getAttribute("playerToken");

        if (roomId == null || token == null) {
            resp.sendRedirect(req.getContextPath() + "/join");
            return;
        }

        GameRoom room = RoomRepository.get(roomId);
        if (room == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Room non trovata");
            return;
        }

        Integer myIdx = room.indexByToken(token);
        if (myIdx == null) {
            session.removeAttribute("playerToken");
            session.removeAttribute("roomId");
            resp.sendRedirect(req.getContextPath() + "/join?room=" + UrlUtil.enc(roomId));
            return;
        }

        // Il forward alla JSP va fatto FUORI dal lock della stanza:
        // qui si raccoglie solo l'esito.
        String startError = tryStart(room, token);
        if (startError != null) {
            req.setAttribute("error", startError);
            req.setAttribute("roomId", roomId);
            req.getRequestDispatcher("/WEB-INF/jsp/room.jsp").forward(req, resp);
            return;
        }

        resp.sendRedirect(req.getContextPath() + "/room?room=" + UrlUtil.enc(roomId));
    }

    /** Avvia la partita. Ritorna null se ok, altrimenti il messaggio d'errore. */
    private static String tryStart(GameRoom room, String token) {
        room.lock().lock();
        try {
            if (room.state().getPhase() != Phase.WAITING_FOR_PLAYERS) {
                return "Il gioco è già iniziato.";
            }

            if (!room.isHost(token)) {
                return "Solo il creatore della stanza può avviare il gioco.";
            }

            if (!GameLifecycle.startMatch(room.state(), room.engine())) {
                return "Servono almeno 2 giocatori per iniziare.";
            }

            room.touch();
            return null;
        } finally {
            room.lock().unlock();
        }
    }
}
