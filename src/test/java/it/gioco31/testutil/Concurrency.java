package it.gioco31.testutil;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runner per i test concorrenti: partenza sincronizzata e attesa con timeout. */
public final class Concurrency {

    private static final long TIMEOUT_SECONDS = 30;

    private Concurrency() {}

    /**
     * Fa partire tutti i task nello stesso istante (latch, niente sleep) e ne
     * attende l'esito con timeout: se qualcosa si blocca il test fallisce
     * invece di restare appeso. Le eccezioni dei task risalgono dalla get().
     */
    public static void runAllTogether(List<Callable<Void>> tasks) throws Exception {
        int n = tasks.size();
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch ready = new CountDownLatch(n);
            CountDownLatch go = new CountDownLatch(1);

            List<Future<Void>> futures = new ArrayList<>(n);
            for (Callable<Void> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }

            assertTrue(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "i thread non sono pronti in tempo");
            go.countDown();

            for (Future<Void> f : futures) {
                f.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
