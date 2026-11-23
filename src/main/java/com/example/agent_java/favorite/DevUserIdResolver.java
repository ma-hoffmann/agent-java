package com.example.agent_java.favorite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Übergangslösung ohne Anmeldung: Alle Anfragen gehören demselben Benutzer.
 * Nicht für den produktiven Einsatz gedacht, weil dann alle Besucher dieselben Favoriten sehen.
 */
@Component
public class DevUserIdResolver implements UserIdResolver {

    private final String userId;

    public DevUserIdResolver(@Value("${application.favorites.dev-user-id:dev-user}") String userId) {
        this.userId = userId;
    }

    @Override
    public Mono<String> currentUserId() {
        return Mono.just(userId);
    }
}
