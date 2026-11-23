package com.example.agent_java.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.util.List;

/**
 * Die DTOs des Vertrags {@code openapi/agent-api.yaml}, so wie sie an die Clients gehen. Sie sind bewusst
 * getrennt von den rohen Antworten der Endpunkte: Ändert sich ein Endpunkt, ändert sich nur das Mapping
 * (siehe {@code service.ToolMapper}), nicht dieses Format.
 *
 * <p>Die Namen der Felder und die Werte von {@code status}, {@code tool} und {@code code} sind Teil des Vertrags.
 */
public final class AgentApi {

    private AgentApi() {
    }

    // ------------------------------------------------------------------ Anfrage und Antwort

    /** Antwort von {@code POST /api/v1/agent}: entweder {@link AgentOk} oder {@link AgentMessage}. */
    public sealed interface AgentResponse permits AgentOk, AgentMessage {

        String status();

        String requestId();

        long durationMs();

        /** Kopie mit der Dauer der gesamten Anfrage (Eingang bis Ausgang des Controllers). */
        AgentResponse withDurationMs(long durationMs);
    }

    /** Mindestens ein Tool wurde aufgerufen. Ein Element pro Aufruf; jedes kann für sich ok oder fehlerhaft sein. */
    public record AgentOk(String status, String requestId, long durationMs, List<ToolResult> results)
            implements AgentResponse {

        public static AgentOk of(String requestId, List<ToolResult> results) {
            return new AgentOk("ok", requestId, 0, List.copyOf(results));
        }

        @Override
        public AgentResponse withDurationMs(long durationMs) {
            return new AgentOk(status, requestId, durationMs, results);
        }
    }

    /** Kein Tool wurde aufgerufen: eine Rückfrage oder ein Hinweis von Claude, als reiner Text. */
    public record AgentMessage(String status, String requestId, long durationMs, String message)
            implements AgentResponse {

        public static AgentMessage of(String requestId, String message) {
            return new AgentMessage("message", requestId, 0, message);
        }

        @Override
        public AgentResponse withDurationMs(long durationMs) {
            return new AgentMessage(status, requestId, durationMs, message);
        }
    }

    // ------------------------------------------------------------------ Tool-Ergebnisse

    /** Ergebnis eines Tool-Aufrufs. Der Typ hängt vom Tool ab ({@code tool} im JSON). */
    public sealed interface ToolResult permits CourtsResult, BookingsResult {

        String tool();

        String status();
    }

    public static final String TOOL_STATUS_OK = "ok";
    public static final String TOOL_STATUS_ERROR = "error";

    /** Fehlercodes aus dem Vertrag. */
    public static final String INVALID_ARGUMENTS = "invalid_arguments";
    public static final String UPSTREAM_UNAVAILABLE = "upstream_unavailable";
    public static final String UPSTREAM_ERROR = "upstream_error";

    /** Kurze Meldung für den Benutzer, ohne technische Details, URLs oder Parameterwerte. */
    public record ToolError(String code, String message) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CourtsResult(String tool, ListCourtsArguments arguments, String status, CourtsData data,
                               ToolError error) implements ToolResult {

        public static final String TOOL = "list_courts";

        public static CourtsResult ok(ListCourtsArguments arguments, CourtsData data) {
            return new CourtsResult(TOOL, arguments, TOOL_STATUS_OK, data, null);
        }

        public static CourtsResult failed(ListCourtsArguments arguments, ToolError error) {
            return new CourtsResult(TOOL, arguments, TOOL_STATUS_ERROR, null, error);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BookingsResult(String tool, ListBookingsArguments arguments, String status, BookingsData data,
                                 ToolError error) implements ToolResult {

        public static final String TOOL = "list_bookings";

        public static BookingsResult ok(ListBookingsArguments arguments, BookingsData data) {
            return new BookingsResult(TOOL, arguments, TOOL_STATUS_OK, data, null);
        }

        public static BookingsResult failed(ListBookingsArguments arguments, ToolError error) {
            return new BookingsResult(TOOL, arguments, TOOL_STATUS_ERROR, null, error);
        }
    }

    // ------------------------------------------------------------------ Argumente und Daten je Tool

    /** Die von Claude gewählten Parameter. {@code null} bei {@code invalid_arguments}, wenn nichts Gültiges vorliegt. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ListCourtsArguments(LocalDate date) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ListBookingsArguments(LocalDate date) {
    }

    public record CourtsData(LocalDate date, List<Court> courts) {

        public record Court(String court, List<Slot> slots) {
        }

        /** Zeitfenster; {@code start} und {@code end} im Format HH:mm. */
        public record Slot(String start, String end, boolean free) {
        }
    }

    public record BookingsData(LocalDate date, List<Booking> bookings) {

        /** {@code member} ist ein personenbezogener Name und darf nicht geloggt werden. */
        public record Booking(String court, String start, String end, String member) {
        }
    }
}
