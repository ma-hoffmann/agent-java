package com.example.agent_java.service;

import com.anthropic.client.AnthropicClientAsync;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.models.messages.Usage;
import com.example.agent_java.api.AgentApi;
import com.example.agent_java.api.AgentApi.AgentMessage;
import com.example.agent_java.api.AgentApi.AgentOk;
import com.example.agent_java.api.AgentApi.AgentResponse;
import com.example.agent_java.api.AgentApi.BookingsResult;
import com.example.agent_java.api.AgentApi.CourtsResult;
import com.example.agent_java.conf.EndpointCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentServiceTest {

    private static final String COURTS_JSON =
            "{\"date\":\"2026-11-23\",\"courts\":[{\"court\":\"Court 1\",\"slots\":[{\"time\":\"17:00-18:00\",\"free\":true}]}]}";
    private static final String BOOKINGS_JSON =
            "{\"date\":\"2026-11-23\",\"bookings\":[{\"court\":\"Court 1\",\"time\":\"18:00-19:00\",\"member\":\"Anna\"}]}";

    private AnthropicClientAsync client;
    private ToolCatalogService toolCatalog;
    private AgentService service;

    @BeforeEach
    void setUp() {
        client = mock(AnthropicClientAsync.class, RETURNS_DEEP_STUBS);
        toolCatalog = mock(ToolCatalogService.class);
        when(toolCatalog.hasEndpoints()).thenReturn(true);
        when(toolCatalog.tools()).thenReturn(List.of());
        when(toolCatalog.hasTool(any())).thenAnswer(inv ->
                List.of("list_courts", "list_bookings").contains(inv.<String>getArgument(0)));
        EndpointCatalog catalog = new EndpointCatalog("key", List.of(
                new EndpointCatalog.Endpoint("list_courts", "d", "GET", "u", List.of()),
                new EndpointCatalog.Endpoint("list_bookings", "d", "GET", "u", List.of())));
        service = new AgentService(client, toolCatalog,
                new ToolMapperRegistry(List.of(new CourtsToolMapper(), new BookingsToolMapper()), catalog),
                Duration.ofSeconds(5));
    }

    // ------------------------------------------------------------------ Hilfen

    private static ToolUseBlock toolUse(String name, String date) {
        ToolUseBlock block = mock(ToolUseBlock.class);
        when(block.name()).thenReturn(name);
        when(block._input()).thenReturn(JsonValue.from(Map.of("date", date)));
        return block;
    }

    private static Message message(StopReason stop, ContentBlock... blocks) {
        Message message = mock(Message.class);
        Usage usage = mock(Usage.class);
        when(usage.inputTokens()).thenReturn(100L);
        when(usage.outputTokens()).thenReturn(20L);
        when(message.usage()).thenReturn(usage);
        when(message.stopReason()).thenReturn(Optional.of(stop));
        when(message.content()).thenReturn(List.of(blocks));
        return message;
    }

    private static Message withText(String text) {
        TextBlock block = mock(TextBlock.class);
        when(block.text()).thenReturn(text);
        return message(StopReason.END_TURN, ContentBlock.ofText(block));
    }

    private void claudeAnswers(Message message) {
        when(client.messages().create(any(MessageCreateParams.class)))
                .thenReturn(CompletableFuture.completedFuture(message));
    }

    private void toolReturns(String tool, String body) {
        when(toolCatalog.execute(eq(tool), any(), any())).thenReturn(Mono.just(
                ToolCatalogService.ToolResult.ok(Map.of("date", "2026-11-23"), body)));
    }

    // ------------------------------------------------------------------ Tests

    @Test
    void einToolAufrufWirdAusgefuehrtUndGemappt() {
        claudeAnswers(message(StopReason.TOOL_USE, ContentBlock.ofToolUse(toolUse("list_courts", "2026-11-23"))));
        toolReturns("list_courts", COURTS_JSON);

        AgentResponse response = service.ask("Welche Courts sind morgen frei?", "req1").block();

        assertThat(response).isInstanceOf(AgentOk.class);
        AgentOk ok = (AgentOk) response;
        assertThat(ok.status()).isEqualTo("ok");
        assertThat(ok.requestId()).isEqualTo("req1");
        assertThat(ok.results()).hasSize(1);
        CourtsResult courts = (CourtsResult) ok.results().get(0);
        assertThat(courts.status()).isEqualTo("ok");
        assertThat(courts.data().courts().get(0).slots().get(0).start()).isEqualTo("17:00");
    }

    @Test
    void mehrereToolsInEinerRundeBehaltenDieReihenfolgeVonClaude() {
        claudeAnswers(message(StopReason.TOOL_USE,
                ContentBlock.ofToolUse(toolUse("list_bookings", "2026-11-23")),
                ContentBlock.ofToolUse(toolUse("list_courts", "2026-11-23"))));
        // Das erste Tool ist langsamer: Die Reihenfolge der Ergebnisse darf trotzdem nicht kippen.
        when(toolCatalog.execute(eq("list_bookings"), any(), any())).thenReturn(Mono.just(
                ToolCatalogService.ToolResult.ok(Map.of("date", "2026-11-23"), BOOKINGS_JSON))
                .delayElement(Duration.ofMillis(150)));
        toolReturns("list_courts", COURTS_JSON);

        AgentOk ok = (AgentOk) service.ask("Wer hat gebucht und was ist frei?", "req2").block();

        assertThat(ok.results()).hasSize(2);
        assertThat(ok.results().get(0)).isInstanceOf(BookingsResult.class);
        assertThat(ok.results().get(1)).isInstanceOf(CourtsResult.class);
    }

    @Test
    void einAusgefallenerEndpunktVerdecktDieAnderenErgebnisseNicht() {
        claudeAnswers(message(StopReason.TOOL_USE,
                ContentBlock.ofToolUse(toolUse("list_courts", "2026-11-23")),
                ContentBlock.ofToolUse(toolUse("list_bookings", "2026-11-23"))));
        toolReturns("list_courts", COURTS_JSON);
        when(toolCatalog.execute(eq("list_bookings"), any(), any())).thenReturn(Mono.just(
                ToolCatalogService.ToolResult.failed(Map.of("date", "2026-11-23"),
                        AgentApi.UPSTREAM_UNAVAILABLE, "Der Endpunkt ist nicht erreichbar.")));

        AgentOk ok = (AgentOk) service.ask("frage", "req3").block();

        assertThat(ok.results().get(0).status()).isEqualTo("ok");
        BookingsResult failed = (BookingsResult) ok.results().get(1);
        assertThat(failed.status()).isEqualTo("error");
        assertThat(failed.error().code()).isEqualTo(AgentApi.UPSTREAM_UNAVAILABLE);
    }

    @Test
    void ohneToolAufrufWirdClaudesTextZurRueckfrage() {
        claudeAnswers(withText("  Für welches Datum soll ich die freien Courts suchen?  "));

        AgentResponse response = service.ask("Welche Courts sind frei?", "req4").block();

        assertThat(response).isInstanceOf(AgentMessage.class);
        assertThat(((AgentMessage) response).message()).isEqualTo("Für welches Datum soll ich die freien Courts suchen?");
        verify(toolCatalog, never()).execute(any(), any(), any());
    }

    @Test
    void leereTextAntwortBekommtEinenStandardText() {
        claudeAnswers(withText("   "));

        AgentMessage response = (AgentMessage) service.ask("frage", "req5").block();

        assertThat(response.message()).isNotBlank();
    }

    @Test
    void unbekanntesToolWirdIgnoriert() {
        claudeAnswers(message(StopReason.TOOL_USE, ContentBlock.ofToolUse(toolUse("delete_everything", "2026-11-23"))));

        AgentResponse response = service.ask("frage", "req6").block();

        assertThat(response).isInstanceOf(AgentMessage.class);
        verify(toolCatalog, never()).execute(any(), any(), any());
    }

    @Test
    void claudeFehlerWirdZu502MitRequestId() {
        when(client.messages().create(any(MessageCreateParams.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("API kaputt, geheimer Text")));

        Throwable error = org.junit.jupiter.api.Assertions.assertThrows(AgentFailedException.class,
                () -> service.ask("frage", "req7").block());

        AgentFailedException failed = (AgentFailedException) error;
        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(failed.getBody().getProperties()).containsEntry("requestId", "req7");
        assertThat(failed.getBody().getDetail()).doesNotContain("geheimer Text");
    }

    @Test
    void zuLangsamesClaudeWirdZu504() {
        AgentService slow = new AgentService(client, toolCatalog,
                new ToolMapperRegistry(List.of(new CourtsToolMapper(), new BookingsToolMapper()),
                        new EndpointCatalog("key", List.of())),
                Duration.ofMillis(100));
        when(client.messages().create(any(MessageCreateParams.class))).thenReturn(new CompletableFuture<>());

        AgentFailedException failed = org.junit.jupiter.api.Assertions.assertThrows(AgentFailedException.class,
                () -> slow.ask("frage", "req8").block());

        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
    }

    @Test
    void ohneEndpunkteGibtEsEineNachrichtUndKeinenClaudeAufruf() {
        when(toolCatalog.hasEndpoints()).thenReturn(false);

        AgentResponse response = service.ask("frage", "req9").block();

        assertThat(response).isInstanceOf(AgentMessage.class);
        verify(client, never()).messages();
    }

    @Test
    void anfrageNutztEineRundeOhneThinkingUndOhneStrukturierteAusgabe() {
        claudeAnswers(withText("Rückfrage?"));

        service.ask("Frage?", "req10").block();

        ArgumentCaptor<MessageCreateParams> captor = ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(client.messages()).create(captor.capture());
        MessageCreateParams params = captor.getValue();
        assertThat(params.thinking()).isEmpty();
        assertThat(params.outputConfig()).isEmpty();
        assertThat(params.maxTokens()).isLessThanOrEqualTo(1024);
        assertThat(params.system().toString()).contains("Heute ist");
    }

    @Test
    void responseStatusExceptionKlasseBleibtUnberuehrt() {
        // Sicherstellen, dass AgentFailedException keine ResponseStatusException ist (eigener Pfad im Handler).
        assertThat(ResponseStatusException.class.isAssignableFrom(AgentFailedException.class)).isFalse();
    }
}
