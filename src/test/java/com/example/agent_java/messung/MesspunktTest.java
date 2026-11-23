package com.example.agent_java.messung;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MesspunktTest {

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attach() {
        logger = (Logger) LoggerFactory.getLogger("messpunkt-test");
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    private List<String> zeilen() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    void schreibtStartUndEndeMitDurchlaufzeit() throws InterruptedException {
        Messpunkt messpunkt = Messpunkt.start(logger, "test.abschnitt", "anfrage=abc runde=1");
        Thread.sleep(30);
        long millis = messpunkt.ende("ok");

        assertThat(millis).isGreaterThanOrEqualTo(30);
        assertThat(zeilen()).hasSize(2);
        assertThat(zeilen().get(0)).isEqualTo("MESSPUNKT test.abschnitt START anfrage=abc runde=1");
        assertThat(zeilen().get(1))
                .startsWith("MESSPUNKT test.abschnitt ENDE Durchlaufzeit=")
                .contains(" ms | Start=", " | Ende=", " | anfrage=abc runde=1 | ergebnis=ok");
    }

    @Test
    void endeWirdNurEinmalGeloggt() {
        try (Messpunkt messpunkt = Messpunkt.start(logger, "test.einmal", "")) {
            messpunkt.ende("ok");
            messpunkt.ende("nochmal");
        }

        assertThat(zeilen()).hasSize(2);
    }

    @Test
    void closeOhneEndeLoggtUnterbrochen() {
        try (Messpunkt ignored = Messpunkt.start(logger, "test.abbruch", "")) {
            // kein ende() aufgerufen, z. B. wegen einer Exception
        }

        assertThat(zeilen().get(1)).contains("ENDE Durchlaufzeit=").endsWith("ergebnis=unterbrochen");
    }

    @Test
    void fehlerLoggtNurDenTypNichtDenText() {
        Messpunkt messpunkt = Messpunkt.start(logger, "test.fehler", "");
        messpunkt.fehler(new IllegalStateException("geheimer Text der Benutzerfrage"));

        assertThat(zeilen().get(1)).endsWith("ergebnis=fehler IllegalStateException");
        assertThat(String.join("\n", zeilen())).doesNotContain("geheimer Text");
    }

    @Test
    void leereDetailsErzeugenKeineLeerenTrennstriche() {
        Messpunkt messpunkt = Messpunkt.start(logger, "test.leer", "");
        messpunkt.ende("ok");

        assertThat(zeilen().get(0)).isEqualTo("MESSPUNKT test.leer START");
        assertThat(zeilen().get(1)).doesNotContain("|  |").endsWith("| ergebnis=ok");
    }
}
