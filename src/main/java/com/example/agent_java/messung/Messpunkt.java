package com.example.agent_java.messung;

import org.slf4j.Logger;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Misst die Durchlaufzeit eines Abschnitts und schreibt sie ins Log: je eine Zeile bei START und bei ENDE.
 *
 * <p>Jede Zeile beginnt mit {@code MESSPUNKT <name>}. Der Name steht als Textkonstante an genau der Stelle im
 * Code, die er misst. So findet man jede Logzeile mit {@code grep -rn '"agent.claude-runde"' src/main}.
 *
 * <pre>
 * MESSPUNKT agent.claude-runde START anfrage=ab12cd34 runde=1
 * MESSPUNKT agent.claude-runde ENDE Durchlaufzeit=1590 ms | Start=13:09:01.100 | Ende=13:09:02.690 | anfrage=ab12cd34 runde=1 | ergebnis=ok ...
 * </pre>
 *
 * <p>Verwendung mit try-with-resources: Wird {@link #ende(String)} nicht aufgerufen (Exception, Abbruch),
 * schreibt {@link #close()} trotzdem die ENDE-Zeile, mit ergebnis=unterbrochen. Das Ende wird nur einmal
 * geloggt und darf von einem anderen Thread kommen als der Start.
 */
public final class Messpunkt implements AutoCloseable {

    private static final DateTimeFormatter UHRZEIT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final Logger log;
    private final String name;
    private final String details;
    private final long startNanos = System.nanoTime();
    private final LocalTime startZeit = LocalTime.now();
    private final AtomicBoolean beendet = new AtomicBoolean();

    private Messpunkt(Logger log, String name, String details) {
        this.log = log;
        this.name = name;
        this.details = details == null ? "" : details;
    }

    /** Startet die Messung und schreibt die START-Zeile. {@code details} z. B. "anfrage=ab12cd34 runde=1". */
    public static Messpunkt start(Logger log, String name, String details) {
        Messpunkt messpunkt = new Messpunkt(log, name, details);
        log.info("MESSPUNKT {} START{}", name, messpunkt.details.isEmpty() ? "" : " " + messpunkt.details);
        return messpunkt;
    }

    /** Beendet die Messung und schreibt die ENDE-Zeile mit der Durchlaufzeit. Gibt die Zeit in ms zurück. */
    public long ende(String ergebnis) {
        long millis = (System.nanoTime() - startNanos) / 1_000_000;
        if (beendet.compareAndSet(false, true)) {
            log.info("MESSPUNKT {} ENDE Durchlaufzeit={} ms | Start={} | Ende={}{} | ergebnis={}",
                    name, millis, UHRZEIT.format(startZeit), UHRZEIT.format(LocalTime.now()),
                    details.isEmpty() ? "" : " | " + details, ergebnis);
        }
        return millis;
    }

    /** Beendet die Messung mit einem Fehler. Es wird nur der Typ der Exception geloggt, nie ihr Text. */
    public long fehler(Throwable fehler) {
        return ende("fehler " + fehler.getClass().getSimpleName());
    }

    @Override
    public void close() {
        ende("unterbrochen");
    }
}
