package com.example.agent_java.service;

import com.example.agent_java.api.AgentApi;
import com.example.agent_java.api.AgentApi.BookingsResult;
import com.example.agent_java.api.AgentApi.CourtsResult;
import com.example.agent_java.api.AgentApi.ToolResult;
import com.example.agent_java.conf.EndpointCatalog;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class ToolMapperTest {

    private static final EndpointCatalog CATALOG = new EndpointCatalog("key", List.of(
            new EndpointCatalog.Endpoint("list_courts", "d", "GET", "http://x/{date}", List.of()),
            new EndpointCatalog.Endpoint("list_bookings", "d", "GET", "http://x/{date}", List.of())));

    private final ToolMapperRegistry registry =
            new ToolMapperRegistry(List.of(new CourtsToolMapper(), new BookingsToolMapper()), CATALOG);

    private static final Map<String, Object> ARGS = Map.of("date", "2026-11-23");

    private ToolResult map(String tool, String body) {
        return registry.map(tool, ToolCatalogService.ToolResult.ok(ARGS, body));
    }

    @Test
    void courtsWerdenAufDenVertragAbgebildetUndZeitfensterZerlegt() {
        ToolResult result = map("list_courts", """
                {"date":"2026-11-23","courts":[{"court":"Court 1","slots":[
                  {"time":"17:00-18:00","free":false},{"time":"18:00-19:00","free":true}]}]}""");

        CourtsResult courts = (CourtsResult) result;
        assertThat(courts.status()).isEqualTo("ok");
        assertThat(courts.arguments().date()).isEqualTo(LocalDate.of(2026, 11, 23));
        assertThat(courts.data().date()).isEqualTo(LocalDate.of(2026, 11, 23));
        assertThat(courts.data().courts()).hasSize(1);
        assertThat(courts.data().courts().get(0).slots()).extracting("start", "end", "free")
                .containsExactly(tuple("17:00", "18:00", false), tuple("18:00", "19:00", true));
        assertThat(courts.error()).isNull();
    }

    @Test
    void bookingsWerdenAufDenVertragAbgebildet() {
        ToolResult result = map("list_bookings", """
                {"date":"2026-11-23","bookings":[{"court":"Court 2","time":"19:00-20:00","member":"Anna"}]}""");

        BookingsResult bookings = (BookingsResult) result;
        assertThat(bookings.data().bookings()).hasSize(1);
        assertThat(bookings.data().bookings().get(0).start()).isEqualTo("19:00");
        assertThat(bookings.data().bookings().get(0).end()).isEqualTo("20:00");
        assertThat(bookings.data().bookings().get(0).member()).isEqualTo("Anna");
    }

    @Test
    void unerwartetesFormatWirdZuUpstreamErrorOhneDatenImFehlertext() {
        // "free" fehlt, und "Anna" ist ein Wert, der nicht in der Meldung auftauchen darf.
        ToolResult result = map("list_courts", """
                {"date":"2026-11-23","courts":[{"court":"Anna","slots":[{"time":"17:00-18:00"}]}]}""");

        CourtsResult courts = (CourtsResult) result;
        assertThat(courts.status()).isEqualTo("error");
        assertThat(courts.data()).isNull();
        assertThat(courts.error().code()).isEqualTo(AgentApi.UPSTREAM_ERROR);
        assertThat(courts.error().message()).doesNotContain("Anna");
    }

    @Test
    void ungueltigesZeitfensterUndKaputtesJsonSindUpstreamError() {
        assertThat(((CourtsResult) map("list_courts",
                "{\"date\":\"2026-11-23\",\"courts\":[{\"court\":\"C\",\"slots\":[{\"time\":\"25:00-26:00\",\"free\":true}]}]}"))
                .error().code()).isEqualTo(AgentApi.UPSTREAM_ERROR);
        assertThat(((CourtsResult) map("list_courts", "das ist kein json")).error().code())
                .isEqualTo(AgentApi.UPSTREAM_ERROR);
        assertThat(((CourtsResult) map("list_courts", "")).error().code()).isEqualTo(AgentApi.UPSTREAM_ERROR);
    }

    @Test
    void fehlerDesAufrufsBleibenErhaltenUndArgumenteFehlenWennUngueltig() {
        ToolResult failed = registry.map("list_courts", ToolCatalogService.ToolResult.failed(
                Map.of("date", "morgen"), AgentApi.INVALID_ARGUMENTS, "Parameter 'date' ungültig: ..."));

        CourtsResult courts = (CourtsResult) failed;
        assertThat(courts.status()).isEqualTo("error");
        assertThat(courts.error().code()).isEqualTo(AgentApi.INVALID_ARGUMENTS);
        assertThat(courts.arguments()).isNull();   // "morgen" ist kein Datum, also keine Argumente im Ergebnis
    }

    @Test
    void startPruefungVerlangtEinMappingProEndpunkt() {
        EndpointCatalog withUnknown = new EndpointCatalog("key", List.of(
                new EndpointCatalog.Endpoint("list_courts", "d", "GET", "u", List.of()),
                new EndpointCatalog.Endpoint("book_court", "d", "GET", "u", List.of())));

        assertThatThrownBy(() -> new ToolMapperRegistry(List.of(new CourtsToolMapper()), withUnknown))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("book_court");
    }
}
