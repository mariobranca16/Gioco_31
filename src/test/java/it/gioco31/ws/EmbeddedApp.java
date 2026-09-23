package it.gioco31.ws;

import org.apache.catalina.Context;
import org.apache.catalina.WebResourceRoot;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.webresources.DirResourceSet;
import org.apache.catalina.webresources.StandardRoot;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * L'applicazione vera dentro un Tomcat embedded, su una porta libera scelta
 * dal sistema. Serve ai test end-to-end: l'handshake del WebSocket passa per
 * {@link HttpSessionConfigurator}, che ha bisogno di una HttpSession reale
 * creata dal servlet di join — cosa che nessun test con sessioni finte può
 * riprodurre.
 *
 * <p>Le classi compilate stanno in {@code target/classes}, non dentro
 * {@code src/main/webapp}: vengono montate come {@code /WEB-INF/classes} così
 * che la scansione delle annotazioni trovi servlet, listener e
 * {@code @ServerEndpoint} esattamente come nel war.
 */
final class EmbeddedApp implements AutoCloseable {

    static final String CONTEXT_PATH = "/gioco31";

    private final Tomcat tomcat;
    private final Path baseDir;
    private final int port;

    private EmbeddedApp(Tomcat tomcat, Path baseDir, int port) {
        this.tomcat = tomcat;
        this.baseDir = baseDir;
        this.port = port;
    }

    static EmbeddedApp start() throws Exception {
        // Il container è chiacchierone e i test ne accendono e spengono uno per
        // classe: senza questo, l'output utile annega nei log di avvio.
        Logger.getLogger("org.apache").setLevel(Level.WARNING);

        Path baseDir = Files.createTempDirectory("gioco31-e2e");

        Tomcat tomcat = new Tomcat();
        tomcat.setBaseDir(baseDir.toString());
        tomcat.setPort(0); // porta effimera: due esecuzioni in parallelo non si pestano
        tomcat.setAddDefaultWebXmlToWebapp(true);

        Connector connector = tomcat.getConnector(); // crea il connettore prima dell'avvio
        connector.setProperty("address", "127.0.0.1");

        File webapp = new File("src/main/webapp").getAbsoluteFile();
        if (!webapp.isDirectory()) {
            throw new IllegalStateException("webapp non trovata: " + webapp
                    + " (i test vanno lanciati dalla radice del progetto)");
        }

        Context ctx = tomcat.addWebapp(CONTEXT_PATH, webapp.getAbsolutePath());

        // Il registro delle stanze e quello delle sessioni sono campi statici:
        // con il classloader isolato del webapp il test ne vedrebbe una copia
        // diversa da quella del server, e non potrebbe verificare nulla di
        // ciò che succede dentro. Delegando prima al classloader di sistema
        // (dove le stesse classi stanno già, via target/classes) server e test
        // condividono le stesse classi.
        ctx.setParentClassLoader(EmbeddedApp.class.getClassLoader());
        if (ctx instanceof StandardContext standard) standard.setDelegate(true);

        File classes = new File("target/classes").getAbsoluteFile();
        if (!classes.isDirectory()) {
            throw new IllegalStateException("classi compilate non trovate: " + classes);
        }
        WebResourceRoot resources = new StandardRoot(ctx);
        resources.addPreResources(new DirResourceSet(
                resources, "/WEB-INF/classes", classes.getAbsolutePath(), "/"));
        ctx.setResources(resources);

        tomcat.start();
        return new EmbeddedApp(tomcat, baseDir, connector.getLocalPort());
    }

    String httpUrl(String path) {
        return "http://127.0.0.1:" + port + CONTEXT_PATH + path;
    }

    String wsUrl(String path) {
        return "ws://127.0.0.1:" + port + CONTEXT_PATH + path;
    }

    @Override
    public void close() throws Exception {
        try {
            tomcat.stop();
            tomcat.destroy();
        } finally {
            deleteRecursively(baseDir);
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // File temporanei del container: se restano, poco male.
                }
            });
        }
    }
}
