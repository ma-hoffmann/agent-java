package com.example.agent_java.service;

import com.example.agent_java.api.AgentApi.ToolError;
import com.example.agent_java.api.AgentApi.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bildet die rohe Antwort eines Endpunkts auf das DTO des Vertrags ab. Pro Tool gibt es genau eine Implementierung
 * (Spring-Bean). Der Client sieht nur das DTO, nie das Format des Endpunkts: Ändert sich der Endpunkt, ändert sich
 * nur das Mapping.
 *
 * <p>Ein Mapper muss streng sein: Fehlt ein Feld oder hat es den falschen Typ, wirft er eine
 * {@link MappingException}. Das Ergebnis wird dann zu einem Fehler {@code upstream_error}, statt halbe Daten
 * an den Client zu schicken.
 */
public interface ToolMapper {

    /** Der Tool-Name, wie er in {@code application.agent.endpoints[].id} steht und im Vertrag als {@code tool}. */
    String toolId();

    /** Erzeugt das Ergebnis für eine erfolgreiche Antwort. Wirft {@link MappingException}, wenn sie nicht passt. */
    ToolResult success(Map<String, Object> arguments, JsonNode raw);

    /** Erzeugt das Ergebnis für einen fehlgeschlagenen Aufruf. */
    ToolResult failure(Map<String, Object> arguments, ToolError error);

    /** Die Antwort des Endpunkts hat nicht das erwartete Format. Die Meldung nennt nie Werte aus der Antwort. */
    class MappingException extends RuntimeException {

        public MappingException(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------ Hilfen für die Implementierungen

    /** Das Datum aus den Argumenten oder {@code null}, wenn es fehlt oder ungültig ist. */
    static LocalDate dateArgument(Map<String, Object> arguments) {
        Object value = arguments.get("date");
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(String.valueOf(value));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw new MappingException("Feld '" + field + "' fehlt oder ist kein Text.");
        }
        return value.asText();
    }

    static boolean bool(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isBoolean()) {
            throw new MappingException("Feld '" + field + "' fehlt oder ist kein Boolean.");
        }
        return value.asBoolean();
    }

    static JsonNode array(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) {
            throw new MappingException("Feld '" + field + "' fehlt oder ist keine Liste.");
        }
        return value;
    }

    static LocalDate date(JsonNode node, String field) {
        try {
            return LocalDate.parse(text(node, field));
        } catch (DateTimeParseException e) {
            throw new MappingException("Feld '" + field + "' ist kein Datum (yyyy-MM-dd).");
        }
    }

    Pattern TIME_RANGE = Pattern.compile("^([01]\\d|2[0-3]):([0-5]\\d)\\s*-\\s*([01]\\d|2[0-3]):([0-5]\\d)$");

    /** Zerlegt ein Zeitfenster wie "17:00-18:00" in {@code [start, end]} im Format HH:mm. */
    static String[] timeRange(String value) {
        Matcher matcher = TIME_RANGE.matcher(value.strip());
        if (!matcher.matches()) {
            throw new MappingException("Zeitfenster hat nicht das Format HH:mm-HH:mm.");
        }
        return new String[]{matcher.group(1) + ":" + matcher.group(2), matcher.group(3) + ":" + matcher.group(4)};
    }
}
