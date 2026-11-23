package com.example.agent_java.service;

import com.anthropic.core.JsonValue;
import com.example.agent_java.api.AgentApi;
import com.example.agent_java.conf.EndpointCatalog;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCatalogServiceTest {

    private HttpServer server;
    private ToolCatalogService service;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/ok", exchange -> reply(exchange, 200, "{\"date\":\"2026-11-23\"}"));
        server.createContext("/fehler", exchange -> reply(exchange, 500, "kaputt"));
        server.start();
        String base = "http://localhost:" + server.getAddress().getPort();
        EndpointCatalog catalog = new EndpointCatalog("key", List.of(
                endpoint("t_ok", base + "/ok?date={date}"),
                endpoint("t_fehler", base + "/fehler?date={date}"),
                endpoint("t_weg", "http://localhost:1/nichts?date={date}")));
        service = new ToolCatalogService(catalog);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static EndpointCatalog.Endpoint endpoint(String id, String url) {
        return new EndpointCatalog.Endpoint(id, "d", "GET", url,
                List.of(new EndpointCatalog.Param("date", "date", "Datum", true)));
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private ToolCatalogService.ToolResult run(String tool, Map<String, Object> args) {
        return service.execute(tool, JsonValue.from(args), "test").block();
    }

    @Test
    void erfolgGibtDieRohantwortUndDieArgumenteZurueck() {
        ToolCatalogService.ToolResult result = run("t_ok", Map.of("date", "2026-11-23"));

        assertThat(result.error()).isFalse();
        assertThat(result.body()).isEqualTo("{\"date\":\"2026-11-23\"}");
        assertThat(result.arguments()).containsEntry("date", "2026-11-23");
    }

    @Test
    void fehlerstatusDesEndpunktsIstUpstreamError() {
        ToolCatalogService.ToolResult result = run("t_fehler", Map.of("date", "2026-11-23"));

        assertThat(result.errorCode()).isEqualTo(AgentApi.UPSTREAM_ERROR);
        assertThat(result.errorMessage()).doesNotContain("kaputt").doesNotContain("localhost");
    }

    @Test
    void nichtErreichbarerEndpunktIstUpstreamUnavailable() {
        ToolCatalogService.ToolResult result = run("t_weg", Map.of("date", "2026-11-23"));

        assertThat(result.errorCode()).isEqualTo(AgentApi.UPSTREAM_UNAVAILABLE);
        assertThat(result.errorMessage()).doesNotContain("localhost");
    }

    @Test
    void ungueltigeArgumenteWerdenVorDemAufrufAbgelehnt() {
        assertThat(run("t_ok", Map.of()).errorCode()).isEqualTo(AgentApi.INVALID_ARGUMENTS);
        assertThat(run("t_ok", Map.of("date", "morgen")).errorCode()).isEqualTo(AgentApi.INVALID_ARGUMENTS);
        assertThat(run("t_ok", Map.of("date", "2026-11-23", "extra", "x")).errorCode())
                .isEqualTo(AgentApi.INVALID_ARGUMENTS);
        assertThat(run("gibt_es_nicht", Map.of("date", "2026-11-23")).errorCode())
                .isEqualTo(AgentApi.INVALID_ARGUMENTS);
    }

    @Test
    void hasToolKenntNurDenKatalog() {
        assertThat(service.hasTool("t_ok")).isTrue();
        assertThat(service.hasTool("book_court")).isFalse();
    }
}
