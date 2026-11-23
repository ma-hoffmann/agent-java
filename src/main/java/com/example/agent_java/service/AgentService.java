package com.example.agent_java.service;

import com.anthropic.client.AnthropicClientAsync;
import com.anthropic.models.messages.*;
import com.example.agent_java.api.AgentApi.AgentMessage;
import com.example.agent_java.api.AgentApi.AgentOk;
import com.example.agent_java.api.AgentApi.AgentResponse;
import com.example.agent_java.api.AgentApi.ToolResult;
import com.example.agent_java.messung.Messpunkt;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

/**
 * Beantwortet eine Frage in genau einer Claude-Runde: Claude wählt nur Tool und Parameter, der Server ruft die
 * Endpunkte auf und bildet ihre Antworten auf die DTOs des Vertrags ab ({@link ToolMapperRegistry}). Die Daten
 * der Endpunkte gehen nie an Claude zurück. Dargestellt werden sie vom Client.
 *
 * <p>Ruft Claude kein Tool auf (Rückfrage, oder keine Endpunkt passt), wird sein kurzer Text als
 * {@link AgentMessage} zurückgegeben.
 */
@Slf4j
@Service
public class AgentService {

    private static final long MAX_TOKENS = 4096;
    private static final String NO_TEXT = "Ich konnte die Frage nicht zuordnen. Bitte formuliere sie anders.";

    private static final String SYSTEM_PROMPT = """
            Du bist der Assistent einer Sportanlage bzw. eines Clubs.
            Heute ist %s (%s).

            Deine einzige Aufgabe: Wähle zur Frage des Benutzers die passenden Tools und rufe sie mit den richtigen
            Parametern auf. Die Ergebnisse der Tools siehst du nie, sie werden dem Benutzer direkt angezeigt.
            Du formulierst keine Antwort auf die Frage.

            Verbindliche Regeln:
            1. Rufe alle Tools, die zur Frage nötig sind, gleichzeitig in einer einzigen Antwort auf. Es gibt keine
               zweite Runde.
            2. Wochentage und relative Angaben löst du selbst ab dem heutigen Datum auf, ohne nachzufragen:
               "heute" = heute, "morgen" = heute + 1 Tag, "übermorgen" = heute + 2 Tage, "gestern" = heute - 1 Tag.
               Ein Wochentag bedeutet das nächste Vorkommen ab heute (ist heute dieser Wochentag, dann heute).
            3. Nur wenn sich ein notwendiger Parameter auch mit diesen Regeln nicht ableiten lässt (z. B. gar keine
               Datumsangabe), rufe kein Tool auf und stelle in einem Satz eine kurze Rückfrage.
            4. Passt kein Tool zur Frage, rufe kein Tool auf und erkläre in einem Satz, dass du dazu nichts sagen
               kannst. Beantworte keine Fragen aus eigenem Wissen.
            5. Text schreibst du nur für Rückfragen und Hinweise, in der Sprache des Benutzers und ohne Markdown.
            """;

    private final AnthropicClientAsync agentClient;
    private final ToolCatalogService toolCatalog;
    private final ToolMapperRegistry mappers;
    private final Duration claudeTimeout;

    public AgentService(AnthropicClientAsync agentClient, ToolCatalogService toolCatalog, ToolMapperRegistry mappers,
                        @Value("${application.agent.claude-timeout:45s}") Duration claudeTimeout) {
        this.agentClient = agentClient;
        this.toolCatalog = toolCatalog;
        this.mappers = mappers;
        this.claudeTimeout = claudeTimeout;
    }

    /**
     * Beantwortet eine Frage. {@code requestId} verbindet alle Logzeilen der Anfrage. Das Ergebnis enthält
     * {@code durationMs = 0}, die Dauer setzt der Controller. Fehler beim Aufruf von Claude enden als
     * {@link AgentFailedException} (502 oder 504).
     */
    public Mono<AgentResponse> ask(String userprompt, String requestId) {
        if (!toolCatalog.hasEndpoints()) {
            return Mono.just(AgentMessage.of(requestId, "Es sind keine Endpunkte konfiguriert."));
        }
        return Mono.fromSupplier(() -> buildRequest(userprompt, requestId))
                .flatMap(params -> callClaude(params, requestId))
                .flatMap(message -> interpret(message, requestId));
    }

    private MessageCreateParams buildRequest(String userprompt, String requestId) {
        Messpunkt vorbereitung = Messpunkt.start(log, "agent.request-vorbereiten", "anfrage=" + requestId);
        try {
            LocalDate today = LocalDate.now();
            MessageCreateParams.Builder request = MessageCreateParams.builder()
                    .model(Model.CLAUDE_HAIKU_5_5)
                    .maxTokens(MAX_TOKENS)
//                    .enabledThinking(1024)
                    .system(SYSTEM_PROMPT.formatted(
                            today.format(DateTimeFormatter.ofPattern("EEEE, dd.MM.yyyy", Locale.GERMAN)), today))
                    .addUserMessage(userprompt);
            toolCatalog.tools().forEach(request::addTool);
            MessageCreateParams params = request.build();
            vorbereitung.ende("ok");
            return params;
        } catch (RuntimeException e) {
            vorbereitung.fehler(e);
            throw e;
        }
    }

    private Mono<Message> callClaude(MessageCreateParams params, String requestId) {
        return Mono.defer(() -> {
            Messpunkt aufruf = Messpunkt.start(log, "agent.claude-aufruf", "anfrage=" + requestId);
            return Mono.fromFuture(() -> agentClient.messages().create(params))
                    .timeout(claudeTimeout)
                    .doOnNext(message -> aufruf.ende("ok stop=" + message.stopReason().map(Object::toString).orElse("?")
                            + " input-token=" + message.usage().inputTokens()
                            + " output-token=" + message.usage().outputTokens()))
                    .doOnNext(message -> log.info("TOKENS anfrage={} input={} output={} gesamt={}", requestId,
                            message.usage().inputTokens(), message.usage().outputTokens(),
                            message.usage().inputTokens() + message.usage().outputTokens()))
                    .doOnError(aufruf::fehler)
                    .onErrorMap(e -> {
                        log.error("Claude-Aufruf fehlgeschlagen, anfrage={}", requestId, e);
                        return new AgentFailedException(
                                e instanceof TimeoutException ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY,
                                requestId, e);
                    });
        });
    }

    /** Ruft die von Claude gewählten Tools auf (gleichzeitig, Reihenfolge bleibt) oder gibt seinen Text zurück. */
    private Mono<AgentResponse> interpret(Message message, String requestId) {
        List<ToolUseBlock> calls = new ArrayList<>();
        for (ContentBlock block : message.content()) {
            block.toolUse().ifPresent(call -> {
                if (toolCatalog.hasTool(call.name()) && mappers.hasMapper(call.name())) {
                    calls.add(call);
                } else {
                    log.warn("Claude hat das unbekannte Tool '{}' aufgerufen, es wird ignoriert. anfrage={}",
                            call.name(), requestId);
                }
            });
        }
        if (calls.isEmpty()) {
            return Mono.just(AgentMessage.of(requestId, textOf(message)));
        }
        return Flux.fromIterable(calls)
                .flatMapSequential(call -> runTool(call, requestId))
                .collectList()
                .map(results -> AgentOk.of(requestId, results));
    }

    private Mono<ToolResult> runTool(ToolUseBlock call, String requestId) {
        return Mono.defer(() -> {
            Messpunkt messpunkt = Messpunkt.start(log, "agent.tool",
                    "anfrage=" + requestId + " tool=" + call.name());
            return toolCatalog.execute(call.name(), call._input(), requestId)
                    .map(raw -> mappers.map(call.name(), raw))
                    .doOnNext(result -> messpunkt.ende(result.status()))
                    .doOnError(messpunkt::fehler);
        });
    }

    private static String textOf(Message message) {
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : message.content()) {
            block.text().map(TextBlock::text).ifPresent(text::append);
        }
        String result = text.toString().strip();
        return result.isEmpty() ? NO_TEXT : result;
    }
}
