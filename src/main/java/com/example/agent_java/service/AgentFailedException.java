package com.example.agent_java.service;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * Die Anfrage konnte nicht beantwortet werden, weil Claude nicht erreichbar ist (502) oder zu lange gebraucht
 * hat (504). Wird als {@code application/problem+json} mit {@code requestId} ausgeliefert, siehe den Vertrag.
 * Der Text nennt keine Details, die Ursache steht nur im Log.
 */
public class AgentFailedException extends ErrorResponseException {

    public AgentFailedException(HttpStatus status, String requestId, Throwable cause) {
        super(status, problem(status, requestId), cause);
    }

    private static ProblemDetail problem(HttpStatus status, String requestId) {
        String detail = status == HttpStatus.GATEWAY_TIMEOUT
                ? "Die Anfrage hat zu lange gedauert. Bitte versuche es erneut."
                : "Die Anfrage konnte nicht beantwortet werden. Bitte versuche es erneut.";
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("requestId", requestId);
        return problem;
    }
}
