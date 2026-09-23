package it.gioco31.controller;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Obbliga il browser a rivalidare il codice di pagina a ogni caricamento.
 *
 * <p>Per CSS e immagini basta il {@code ?v=N} nel markup: ogni file è citato
 * nella JSP, quindi si invalida da solo cambiando quel numero. Per i moduli ES
 * no. Nel markup c'è solo main.js; i nove file che importa (net.js, render.js,
 * view.js...) se li va a prendere il browser da sé, senza alcun token. Tomcat
 * non manda Cache-Control per le risorse statiche, quindi quei file resterebbero
 * in cache con la scadenza euristica del browser: dopo un deploy si otterrebbe
 * un main.js nuovo che importa un net.js vecchio — un grafo misto, che è peggio
 * di uno interamente vecchio. Con questo stesso cambio sarebbe già successo: il
 * protocollo sul socket è passato da "ACTION:keep:2" a JSON, e le due metà non
 * si parlerebbero (il server scarta in silenzio, senza un errore da nessuna
 * parte).
 *
 * <p>{@code no-cache} non vieta la cache: impone di chiedere al server prima di
 * usarla. Con l'ETag che Tomcat già manda, un file immutato costa un 304.
 */
@WebFilter("/js/*")
public class JsCacheFilter implements Filter {

    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
            throws IOException, ServletException {
        // Prima della catena: dopo, la risposta può essere già stata committata
        // e l'intestazione verrebbe scartata in silenzio.
        if (resp instanceof HttpServletResponse http) {
            http.setHeader("Cache-Control", "no-cache");
        }
        chain.doFilter(req, resp);
    }
}
