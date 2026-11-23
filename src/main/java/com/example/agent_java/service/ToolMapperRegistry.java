package com.example.agent_java.service;

import com.example.agent_java.api.AgentApi;
import com.example.agent_java.api.AgentApi.ToolError;
import com.example.agent_java.api.AgentApi.ToolResult;
import com.example.agent_java.conf.EndpointCatalog;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sammelt die {@link ToolMapper} und wandelt das rohe Ergebnis eines Tool-Aufrufs in das DTO des Vertrags um.
 * Beim Start wird geprüft, dass jeder Endpunkt im Katalog ein Mapping hat: Ein Tool ohne Mapping hätte kein
 * Format im Vertrag und würde sonst erst beim ersten Aufruf auffallen.
 */
@Slf4j
@Component
public class ToolMapperRegistry {

    private final Map<String, ToolMapper> mappers = new HashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ToolMapperRegistry(List<ToolMapper> mapperList, EndpointCatalog catalog) {
        mapperList.forEach(mapper -> mappers.put(mapper.toolId(), mapper));
        List<String> missing = catalog.endpoints().stream()
                .map(EndpointCatalog.Endpoint::id)
                .filter(id -> !mappers.containsKey(id))
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Für diese Endpunkte aus application.agent.endpoints gibt es kein "
                    + "ToolMapper-Mapping (und damit kein Format im Vertrag): " + missing);
        }
    }

    public boolean hasMapper(String tool) {
        return mappers.containsKey(tool);
    }

    /** Wandelt das rohe Ergebnis in das DTO um. Wirft nie: Ein unpassendes Format wird zu {@code upstream_error}. */
    public ToolResult map(String tool, ToolCatalogService.ToolResult raw) {
        ToolMapper mapper = mappers.get(tool);
        if (raw.error()) {
            return mapper.failure(raw.arguments(), new ToolError(raw.errorCode(), raw.errorMessage()));
        }
        try {
            JsonNode json = objectMapper.readTree(raw.body());
            return mapper.success(raw.arguments(), json);
        } catch (ToolMapper.MappingException | JsonProcessingException e) {
            // Nur der Typ und bei MappingException die Meldung (nennt nur Feldnamen) werden geloggt, nie Daten.
            log.warn("Mapping für Tool {} fehlgeschlagen: {}", tool,
                    e instanceof ToolMapper.MappingException ? e.getMessage() : e.getClass().getSimpleName());
            return mapper.failure(raw.arguments(), new ToolError(AgentApi.UPSTREAM_ERROR,
                    "Die Antwort des Endpunkts hat ein unerwartetes Format."));
        }
    }
}
