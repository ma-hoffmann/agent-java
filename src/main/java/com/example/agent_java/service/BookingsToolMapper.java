package com.example.agent_java.service;

import com.example.agent_java.api.AgentApi.BookingsData;
import com.example.agent_java.api.AgentApi.BookingsResult;
import com.example.agent_java.api.AgentApi.ListBookingsArguments;
import com.example.agent_java.api.AgentApi.ToolError;
import com.example.agent_java.api.AgentApi.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Mapping für {@code list_bookings}. Erwartet die Antwort des Endpunkts im Format
 * {@code {"date": "...", "bookings": [{"court": "...", "time": "17:00-18:00", "member": "..."}]}}.
 */
@Component
public class BookingsToolMapper implements ToolMapper {

    @Override
    public String toolId() {
        return BookingsResult.TOOL;
    }

    @Override
    public ToolResult success(Map<String, Object> arguments, JsonNode raw) {
        List<BookingsData.Booking> bookings = new ArrayList<>();
        for (JsonNode booking : ToolMapper.array(raw, "bookings")) {
            String[] range = ToolMapper.timeRange(ToolMapper.text(booking, "time"));
            bookings.add(new BookingsData.Booking(ToolMapper.text(booking, "court"), range[0], range[1],
                    ToolMapper.text(booking, "member")));
        }
        return BookingsResult.ok(arguments(arguments),
                new BookingsData(ToolMapper.date(raw, "date"), List.copyOf(bookings)));
    }

    @Override
    public ToolResult failure(Map<String, Object> arguments, ToolError error) {
        return BookingsResult.failed(arguments(arguments), error);
    }

    private static ListBookingsArguments arguments(Map<String, Object> arguments) {
        LocalDate date = ToolMapper.dateArgument(arguments);
        return date == null ? null : new ListBookingsArguments(date);
    }
}
