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

        room.lock().lock();
        try {
            if (room.state().getPhase() != Phase.WAITING_FOR_PLAYERS) {
                req.setAttribute("error", "Il gioco è già iniziato.");
                req.setAttribute("roomId", roomId);
                req.getRequestDispatcher("/WEB-INF/jsp/room.jsp").forward(req, resp);
                return;
            }

            if (myIdx != 0) {
                req.setAttribute("error", "Solo il creatore della stanza può avviare il gioco.");
                req.setAttribute("roomId", roomId);
                req.getRequestDispatcher("/WEB-INF/jsp/room.jsp").forward(req, resp);
                return;
            }

            GameLifecycle.resetMatchState(room.state());
            int joined = GameLifecycle.preparePlayersForNewMatch(room.state());

            if (joined < 2) {
                req.setAttribute("error", "Servono almeno 2 giocatori per iniziare.");
                req.setAttribute("roomId", roomId);
                req.getRequestDispatcher("/WEB-INF/jsp/room.jsp").forward(req, resp);
                return;
            }

            room.engine().startRound(room.state());
            room.touch();
        } finally {
            room.lock().unlock();
        }

        resp.sendRedirect(req.getContextPath() + "/room?room=" + UrlUtil.enc(roomId));
    }
}
