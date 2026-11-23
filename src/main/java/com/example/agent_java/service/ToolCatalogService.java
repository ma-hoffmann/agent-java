package com.example.agent_java.service;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;
import com.example.agent_java.api.AgentApi;
import com.example.agent_java.conf.EndpointCatalog;
import com.example.agent_java.conf.EndpointCatalog.Endpoint;
import com.example.agent_java.conf.EndpointCatalog.Param;
import com.example.agent_java.messung.Messpunkt;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

/**
 * Macht aus der Endpunkt-Liste Tools für Claude und führt Tool-Aufrufe aus.
 * Claude kennt nur Name und Parameter; die URL baut ausschließlich dieser Service aus der Liste.
 * Die Antworten der Endpunkte gehen nicht an Claude zurück, sie werden gemappt (siehe {@link ToolMapper})
 * und direkt an den Client geschickt.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolCatalogService {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final EndpointCatalog catalog;
    private final WebClient webClient = WebClient.create();

    /**
     * Das Ergebnis eines Tool-Aufrufs. Bei Erfolg steht die rohe Antwort des Endpunkts in {@code body}, bei einem
     * Fehler sind {@code errorCode} (Wert aus dem Vertrag) und {@code errorMessage} gesetzt. {@code arguments} sind
     * die Parameter, wie Claude sie geliefert hat, auch wenn sie ungültig waren.
     */
    public record ToolResult(Map<String, Object> arguments, String body, String errorCode, String errorMessage) {

        public boolean error() {
            return errorCode != null;
        }

        static ToolResult ok(Map<String, Object> arguments, String body) {
            return new ToolResult(arguments, body, null, null);
        }

        static ToolResult failed(Map<String, Object> arguments, String code, String message) {
            return new ToolResult(arguments, null, code, message);
        }
    }

    public List<Tool> tools() {
        return catalog.endpoints().stream().map(this::toTool).toList();
    }

    public boolean hasEndpoints() {
        return !catalog.endpoints().isEmpty();
    }

    private Tool toTool(Endpoint endpoint) {
        Tool.InputSchema.Properties.Builder properties = Tool.InputSchema.Properties.builder();
        endpoint.params().forEach(param ->
                properties.putAdditionalProperty(param.name(), JsonValue.from(schemaOf(param))));

        List<String> required = endpoint.params().stream().filter(Param::required).map(Param::name).toList();

        return Tool.builder()
                .name(endpoint.id())
                .description(endpoint.description().strip())
                .inputSchema(Tool.InputSchema.builder()
                        .properties(properties.build())
                        .required(required)
                        .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                        .build())
                .build();
    }

    private static Map<String, Object> schemaOf(Param param) {
        Map<String, Object> schema = new LinkedHashMap<>();
        switch (param.type()) {
            case "integer" -> schema.put("type", "integer");
            case "date" -> {
                schema.put("type", "string");
                schema.put("format", "date");
            }
            default -> schema.put("type", "string");
        }
        schema.put("description", param.description());
        return schema;
    }

    public boolean hasTool(String name) {
        return catalog.endpoints().stream().anyMatch(e -> e.id().equals(name));
    }

    /**
     * Prüft den Aufruf gegen die Liste und ruft den Endpunkt auf. Das Mono endet nie mit einem Fehler,
     * Probleme stehen im Ergebnis (siehe {@link ToolResult}).
     */
    public Mono<ToolResult> execute(String name, JsonValue input, String anfrageId) {
        Optional<Endpoint> found = catalog.endpoints().stream().filter(e -> e.id().equals(name)).findFirst();
        if (found.isEmpty()) {
            return Mono.just(ToolResult.failed(Map.of(), AgentApi.INVALID_ARGUMENTS,
                    "Unbekanntes Tool. Es sind nur die bereitgestellten Tools erlaubt."));
        }
        Endpoint endpoint = found.get();

        Map<String, Object> arguments;
        try {
            arguments = input.convert(Map.class);
        } catch (RuntimeException e) {
            return Mono.just(ToolResult.failed(Map.of(), AgentApi.INVALID_ARGUMENTS,
                    "Die Parameter sind kein gültiges JSON-Objekt."));
        }
        if (arguments == null) {
            arguments = Map.of();
        }
        final Map<String, Object> args = arguments;

        String url = endpoint.url();
        for (String key : args.keySet()) {
            if (endpoint.params().stream().noneMatch(p -> p.name().equals(key))) {
                return Mono.just(ToolResult.failed(args, AgentApi.INVALID_ARGUMENTS,
                        "Unbekannter Parameter '" + key + "'."));
            }
        }
        for (Param param : endpoint.params()) {
            Object value = args.get(param.name());
            if (value == null) {
                if (param.required()) {
                    return Mono.just(ToolResult.failed(args, AgentApi.INVALID_ARGUMENTS,
                            "Pflichtparameter '" + param.name() + "' fehlt."));
                }
                url = url.replace("{" + param.name() + "}", "");
                continue;
            }
            String text = String.valueOf(value);
            String problem = validate(param, text);
            if (problem != null) {
                return Mono.just(ToolResult.failed(args, AgentApi.INVALID_ARGUMENTS,
                        "Parameter '" + param.name() + "' ungültig: " + problem));
            }
            url = url.replace("{" + param.name() + "}", URLEncoder.encode(text, StandardCharsets.UTF_8));
        }

        final String finalUrl = url;
        return Mono.defer(() -> {
            // Geloggt werden nur Tool-Name und Methode, nie die URL: Sie enthält die Parameterwerte der Anfrage.
            Messpunkt http = Messpunkt.start(log, "tool.http-aufruf",
                    "anfrage=" + anfrageId + " tool=" + name + " methode=" + endpoint.method().toUpperCase());
            return webClient
                    .method(HttpMethod.valueOf(endpoint.method().toUpperCase()))
                    .uri(finalUrl)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(TIMEOUT)
                    .defaultIfEmpty("")
                    .map(body -> {
                        http.ende("ok zeichen=" + body.length());
                        return ToolResult.ok(args, body);
                    })
                    .onErrorResume(e -> {
                        http.fehler(e);
                        return Mono.just(failureOf(args, e));
                    });
        });
    }

    /** Ordnet eine Exception beim Aufruf eines Endpunkts einem Fehlercode des Vertrags zu. */
    private static ToolResult failureOf(Map<String, Object> arguments, Throwable e) {
        if (e instanceof WebClientResponseException) {
            return ToolResult.failed(arguments, AgentApi.UPSTREAM_ERROR, "Der Endpunkt hat mit einem Fehler geantwortet.");
        }
        if (e instanceof TimeoutException || e instanceof WebClientRequestException) {
            return ToolResult.failed(arguments, AgentApi.UPSTREAM_UNAVAILABLE, "Der Endpunkt ist nicht erreichbar.");
        }
        return ToolResult.failed(arguments, AgentApi.UPSTREAM_ERROR, "Die Antwort des Endpunkts konnte nicht gelesen werden.");
    }

    private static String validate(Param param, String value) {
        switch (param.type()) {
            case "date" -> {
                try {
                    LocalDate.parse(value);
                } catch (DateTimeParseException e) {
                    return "erwartet ein Datum im Format yyyy-MM-dd";
                }
            }
            case "integer" -> {
                try {
                    Long.parseLong(value);
                } catch (NumberFormatException e) {
                    return "erwartet eine ganze Zahl";
                }
            }
            default -> {
                if (value.length() > 200) {
                    return "zu lang";
                }
            }
        }
        return null;
    }
}
