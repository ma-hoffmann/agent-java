package com.example.agent_java.web;

import com.example.agent_java.api.AgentApi;
import com.example.agent_java.api.AgentApi.AgentMessage;
import com.example.agent_java.api.AgentApi.AgentOk;
import com.example.agent_java.api.AgentApi.BookingsResult;
import com.example.agent_java.api.AgentApi.CourtsData;
import com.example.agent_java.api.AgentApi.CourtsResult;
import com.example.agent_java.api.AgentApi.ListBookingsArguments;
import com.example.agent_java.api.AgentApi.ListCourtsArguments;
import com.example.agent_java.api.AgentApi.ToolError;
import com.example.agent_java.service.AgentFailedException;
import com.example.agent_java.service.AgentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Prüft das JSON, das der Controller wirklich ausliefert, gegen den Vertrag {@code openapi/agent-api.yaml}:
 * Feldnamen, Status-Werte, Datumsformat, weggelassene Felder und das Fehlerformat {@code problem+json}.
 */
@WebFluxTest(AgentController.class)
class AgentControllerTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 23);

    @Autowired
    WebTestClient client;

    @MockitoBean
    AgentService service;

    private WebTestClient.ResponseSpec post(Object body) {
        return client.post().uri("/api/v1/agent")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();
    }

    @Test
    void okAntwortHatDieFelderDesVertrags() {
        when(service.ask(eq("Welche Courts sind morgen frei?"), anyString())).thenAnswer(inv ->
                Mono.just(AgentOk.of(inv.getArgument(1), List.of(CourtsResult.ok(
                        new ListCourtsArguments(DAY),
                        new CourtsData(DAY, List.of(new CourtsData.Court("Court 1",
                                List.of(new CourtsData.Slot("17:00", "18:00", true))))))))));

        post(Map.of("input", "Welche Courts sind morgen frei?"))
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo("ok")
                .jsonPath("$.requestId").value(id -> org.assertj.core.api.Assertions.assertThat((String) id).hasSize(8))
                .jsonPath("$.durationMs").isNumber()
                .jsonPath("$.results.length()").isEqualTo(1)
                .jsonPath("$.results[0].tool").isEqualTo("list_courts")
                .jsonPath("$.results[0].status").isEqualTo("ok")
                .jsonPath("$.results[0].arguments.date").isEqualTo("2026-11-23")
                .jsonPath("$.results[0].data.date").isEqualTo("2026-11-23")
                .jsonPath("$.results[0].data.courts[0].court").isEqualTo("Court 1")
                .jsonPath("$.results[0].data.courts[0].slots[0].start").isEqualTo("17:00")
                .jsonPath("$.results[0].data.courts[0].slots[0].end").isEqualTo("18:00")
                .jsonPath("$.results[0].data.courts[0].slots[0].free").isEqualTo(true)
                .jsonPath("$.results[0].error").doesNotExist()
                .jsonPath("$.message").doesNotExist();
    }

    @Test
    void durationMsIstDieZeitImController() {
        when(service.ask(anyString(), anyString())).thenAnswer(inv ->
                Mono.just((AgentApi.AgentResponse) AgentMessage.of(inv.getArgument(1), "Hinweis"))
                        .delayElement(java.time.Duration.ofMillis(300)));

        post(Map.of("input", "frage"))
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.durationMs").value(ms ->
                        org.assertj.core.api.Assertions.assertThat(((Number) ms).longValue()).isGreaterThanOrEqualTo(300));
    }

    @Test
    void messageAntwortHatNurMessageUndKeineResults() {
        when(service.ask(anyString(), anyString())).thenAnswer(inv ->
                Mono.just(AgentMessage.of(inv.getArgument(1), "Für welches Datum?")));

        post(Map.of("input", "Welche Courts sind frei?"))
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("message")
                .jsonPath("$.message").isEqualTo("Für welches Datum?")
                .jsonPath("$.results").doesNotExist();
    }

    @Test
    void fehlerhaftesToolErgebnisHatErrorAberKeineDatenUndKeinArgumentFeldWennUngueltig() {
        when(service.ask(anyString(), anyString())).thenAnswer(inv ->
                Mono.just(AgentOk.of(inv.getArgument(1), List.of(
                        CourtsResult.failed(null, new ToolError(AgentApi.INVALID_ARGUMENTS, "Parameter 'date' fehlt.")),
                        BookingsResult.failed(new ListBookingsArguments(DAY),
                                new ToolError(AgentApi.UPSTREAM_UNAVAILABLE, "Der Endpunkt ist nicht erreichbar."))))));

        post(Map.of("input", "frage"))
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.results[0].tool").isEqualTo("list_courts")
                .jsonPath("$.results[0].status").isEqualTo("error")
                .jsonPath("$.results[0].error.code").isEqualTo("invalid_arguments")
                .jsonPath("$.results[0].error.message").isEqualTo("Parameter 'date' fehlt.")
                .jsonPath("$.results[0].data").doesNotExist()
                .jsonPath("$.results[0].arguments").doesNotExist()
                .jsonPath("$.results[1].tool").isEqualTo("list_bookings")
                .jsonPath("$.results[1].error.code").isEqualTo("upstream_unavailable")
                .jsonPath("$.results[1].arguments.date").isEqualTo("2026-11-23");
    }

    // ------------------------------------------------------------------ Fehler als problem+json

    @Test
    void leereFrageIst400MitFehlerliste() {
        post(Map.of("input", "   "))
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.detail").isEqualTo("Die Anfrage ist ungültig.")
                .jsonPath("$.errors[0].field").isEqualTo("input")
                .jsonPath("$.errors[0].message").isNotEmpty();
    }

    @Test
    void zuLangeFrageIst400AberGenau1000ZeichenSindErlaubt() {
        when(service.ask(anyString(), anyString())).thenAnswer(inv ->
                Mono.just(AgentMessage.of(inv.getArgument(1), "ok")));

        post(Map.of("input", "x".repeat(1000))).expectStatus().isOk();
        post(Map.of("input", "x".repeat(1001)))
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.errors[0].field").isEqualTo("input");
    }

    @Test
    void fehlendesFeldUndKaputtesJsonSind400() {
        post(Map.of("andere", "x")).expectStatus().isBadRequest()
                .expectBody().jsonPath("$.errors[0].field").isEqualTo("input");

        client.post().uri("/api/v1/agent").contentType(MediaType.APPLICATION_JSON).bodyValue("{kaputt")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.detail").isEqualTo("Die Anfrage ist ungültig.");
    }

    @Test
    void anderesContentTypeIst415() {
        client.post().uri("/api/v1/agent").contentType(MediaType.TEXT_PLAIN).bodyValue("frage")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.detail").value(d ->
                        org.assertj.core.api.Assertions.assertThat((String) d).contains("application/json").doesNotContain("is not supported"));
    }

    @Test
    void alterGetAufrufUndAltePfadeSindNichtMehrDa() {
        client.get().uri("/api/v1/agent?input=x").exchange()
                .expectStatus().isEqualTo(HttpStatus.METHOD_NOT_ALLOWED)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.detail").isEqualTo("Die HTTP-Methode ist für diesen Pfad nicht erlaubt.");
        client.post().uri("/agent").contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("input", "x"))
                .exchange().expectStatus().isNotFound();
    }

    @Test
    void claudeNichtErreichbarIst502MitRequestId() {
        when(service.ask(anyString(), anyString())).thenAnswer(inv ->
                Mono.error(new AgentFailedException(HttpStatus.BAD_GATEWAY, inv.getArgument(1), new RuntimeException("intern"))));

        post(Map.of("input", "frage"))
                .expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(502)
                .jsonPath("$.requestId").value(id -> org.assertj.core.api.Assertions.assertThat((String) id).hasSize(8))
                .jsonPath("$.detail").isEqualTo("Die Anfrage konnte nicht beantwortet werden. Bitte versuche es erneut.");
    }

    @Test
    void zeitueberschreitungIst504() {
        when(service.ask(anyString(), anyString())).thenAnswer(inv ->
                Mono.error(new AgentFailedException(HttpStatus.GATEWAY_TIMEOUT, inv.getArgument(1), new RuntimeException())));

        post(Map.of("input", "frage"))
                .expectStatus().isEqualTo(HttpStatus.GATEWAY_TIMEOUT)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.status").isEqualTo(504);
    }
}
