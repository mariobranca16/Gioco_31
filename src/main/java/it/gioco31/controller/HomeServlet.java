package it.gioco31.controller;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

@WebServlet("/")
public class HomeServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String uri = req.getRequestURI();
        String ctx = req.getContextPath();
        String path = (uri != null && uri.startsWith(ctx)) ? uri.substring(ctx.length()) : uri;

        if (path != null && !path.isBlank() && !"/".equals(path)) {
            RequestDispatcher rd = getServletContext().getNamedDispatcher("default");
            if (rd == null) rd = getServletContext().getNamedDispatcher("DefaultServlet");

            if (rd != null) {
                rd.forward(req, resp);
            } else {
                resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            }
            return;
        }

        req.getRequestDispatcher("/WEB-INF/jsp/join.jsp").forward(req, resp);
    }
}
