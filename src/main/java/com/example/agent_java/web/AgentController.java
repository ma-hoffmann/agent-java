package com.example.agent_java.web;

import com.example.agent_java.api.AgentApi.AgentResponse;
import com.example.agent_java.messung.Messpunkt;
import com.example.agent_java.service.AgentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Siehe den Vertrag {@code openapi/agent-api.yaml}, Operation {@code askAgent}. */
@Slf4j
@RestController
@RequiredArgsConstructor
public class AgentController {

    public static final int MAX_INPUT_LENGTH = 1000;

    /** Der Body der Anfrage: {@code {"input": "Welche Courts sind morgen frei?"}}. */
    public record AgentRequest(@NotBlank @Size(max = MAX_INPUT_LENGTH) String input) {
    }

    private final AgentService agentService;

    /**
     * Beantwortet eine Frage. {@code durationMs} in der Antwort ist die Dauer vom Eingang in diesen Controller bis
     * zum Ausgang (die Antwort liegt vor), gemessen mit dem Messpunkt {@code controller.agent}.
     */
    @PostMapping(path = "/api/v1/agent", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<AgentResponse> agent(@Valid @RequestBody AgentRequest request) {
        // Kurze ID, die alle Messpunkte derselben Anfrage im Log verbindet (der Text der Frage wird nie geloggt).
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        Messpunkt controller = Messpunkt.start(log, "controller.agent", "anfrage=" + requestId);
        return agentService.ask(request.input(), requestId)
                .map(response -> response.withDurationMs(controller.ende("status=" + response.status())))
                .doOnError(controller::fehler)
                .doOnCancel(() -> controller.ende("client-abbruch"));
    }
}
