package com.example.agent_java.service;

import com.example.agent_java.api.AgentApi.CourtsData;
import com.example.agent_java.api.AgentApi.CourtsResult;
import com.example.agent_java.api.AgentApi.ListCourtsArguments;
import com.example.agent_java.api.AgentApi.ToolError;
import com.example.agent_java.api.AgentApi.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Mapping für {@code list_courts}. Erwartet die Antwort des Endpunkts im Format
 * {@code {"date": "...", "courts": [{"court": "...", "slots": [{"time": "17:00-18:00", "free": true}]}]}}.
 */
@Component
public class CourtsToolMapper implements ToolMapper {

    @Override
    public String toolId() {
        return CourtsResult.TOOL;
    }

    @Override
    public ToolResult success(Map<String, Object> arguments, JsonNode raw) {
        List<CourtsData.Court> courts = new ArrayList<>();
        for (JsonNode court : ToolMapper.array(raw, "courts")) {
            List<CourtsData.Slot> slots = new ArrayList<>();
            for (JsonNode slot : ToolMapper.array(court, "slots")) {
                String[] range = ToolMapper.timeRange(ToolMapper.text(slot, "time"));
                slots.add(new CourtsData.Slot(range[0], range[1], ToolMapper.bool(slot, "free")));
            }
            courts.add(new CourtsData.Court(ToolMapper.text(court, "court"), List.copyOf(slots)));
        }
        return CourtsResult.ok(arguments(arguments), new CourtsData(ToolMapper.date(raw, "date"), List.copyOf(courts)));
    }

    @Override
    public ToolResult failure(Map<String, Object> arguments, ToolError error) {
        return CourtsResult.failed(arguments(arguments), error);
    }

    private static ListCourtsArguments arguments(Map<String, Object> arguments) {
        LocalDate date = ToolMapper.dateArgument(arguments);
        return date == null ? null : new ListCourtsArguments(date);
    }
}
