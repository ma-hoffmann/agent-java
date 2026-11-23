package com.example.agent_java.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Platzhalter-Endpunkte mit festen Beispieldaten, bis die echten Club-Endpunkte feststehen.
 * Kann gelöscht werden, sobald application.yml auf die echten URLs zeigt.
 */
@RestController
public class MockClubController {

    private static final List<String> COURTS = List.of("Court 1", "Court 2", "Court 3");
    private static final List<String> SLOTS = List.of("17:00-18:00", "18:00-19:00", "19:00-20:00", "20:00-21:00");
    private static final List<String> MEMBERS = List.of("Anna", "Ben", "Chris", "Dana");

    @GetMapping("/mock/courts")
    public Map<String, Object> courts(@RequestParam("date") LocalDate date) {
        List<Map<String, Object>> courts = new ArrayList<>();
        for (int c = 0; c < COURTS.size(); c++) {
            List<Map<String, Object>> slots = new ArrayList<>();
            for (int s = 0; s < SLOTS.size(); s++) {
                slots.add(Map.of("time", SLOTS.get(s), "free", !isBooked(date, c, s)));
            }
            courts.add(Map.of("court", COURTS.get(c), "slots", slots));
        }
        return Map.of("date", date.toString(), "courts", courts);
    }

    @GetMapping("/mock/bookings")
    public Map<String, Object> bookings(@RequestParam("date") LocalDate date) {
        List<Map<String, Object>> bookings = new ArrayList<>();
        for (int c = 0; c < COURTS.size(); c++) {
            for (int s = 0; s < SLOTS.size(); s++) {
                if (isBooked(date, c, s)) {
                    bookings.add(Map.of("court", COURTS.get(c), "time", SLOTS.get(s),
                            "member", MEMBERS.get((c + s) % MEMBERS.size())));
                }
            }
        }
        return Map.of("date", date.toString(), "bookings", bookings);
    }

    private static boolean isBooked(LocalDate date, int court, int slot) {
        return (date.getDayOfYear() + court * 3 + slot * 2) % 3 != 0;
    }
}
