package com.example.agent_java.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.reactive.result.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.server.MethodNotAllowedException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;
import org.springframework.web.server.UnsupportedMediaTypeStatusException;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Liefert alle Fehler der API als {@code application/problem+json} (RFC 9457), wie im Vertrag beschrieben.
 * Die Basisklasse deckt die Standardfälle ab (405, 415, {@code ResponseStatusException} der Favoriten, 502 und 504
 * als {@code AgentFailedException}). Hier angepasst werden nur die Meldungen: deutsch, ohne Interna, und
 * bei Validierungsfehlern mit der Liste {@code errors}.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final String INVALID = "Die Anfrage ist ungültig.";

    @Override
    protected Mono<ResponseEntity<Object>> handleWebExchangeBindException(
            WebExchangeBindException ex, HttpHeaders headers, HttpStatusCode status, ServerWebExchange exchange) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, INVALID);
        List<Map<String, String>> errors = ex.getFieldErrors().stream()
                .map(error -> Map.of("field", error.getField(),
                        "message", Objects.toString(error.getDefaultMessage(), "ungültig")))
                .toList();
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, status, exchange);
    }

    @Override
    protected Mono<ResponseEntity<Object>> handleUnsupportedMediaTypeStatusException(
            UnsupportedMediaTypeStatusException ex, HttpHeaders headers, HttpStatusCode status,
            ServerWebExchange exchange) {
        return handleExceptionInternal(ex, ProblemDetail.forStatusAndDetail(status,
                "Der Content-Type der Anfrage wird nicht unterstützt. Erwartet wird application/json."),
                headers, status, exchange);
    }

    @Override
    protected Mono<ResponseEntity<Object>> handleMethodNotAllowedException(
            MethodNotAllowedException ex, HttpHeaders headers, HttpStatusCode status, ServerWebExchange exchange) {
        return handleExceptionInternal(ex, ProblemDetail.forStatusAndDetail(status,
                "Die HTTP-Methode ist für diesen Pfad nicht erlaubt."), headers, status, exchange);
    }

    /** Z. B. ein Body, der kein gültiges JSON ist. Die Standardmeldung nennt Interna des Servers. */
    @Override
    protected Mono<ResponseEntity<Object>> handleServerWebInputException(
            ServerWebInputException ex, HttpHeaders headers, HttpStatusCode status, ServerWebExchange exchange) {
        return handleExceptionInternal(ex, ProblemDetail.forStatusAndDetail(status, INVALID), headers, status, exchange);
    }
}
