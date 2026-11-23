package com.example.agent_java.favorite;

import reactor.core.publisher.Mono;

/**
 * Ermittelt die ID des angemeldeten Benutzers. Die ID darf nie aus dem Request-Body kommen.
 * Sobald es eine Anmeldung gibt (z. B. JWT/OIDC), kommt hier eine Implementierung hinzu,
 * die die ID aus dem Sicherheitskontext liest, und {@link DevUserIdResolver} entfällt.
 */
public interface UserIdResolver {

    Mono<String> currentUserId();
}
