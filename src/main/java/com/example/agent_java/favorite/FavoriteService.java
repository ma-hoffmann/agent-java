package com.example.agent_java.favorite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

/**
 * Favoriten pro Benutzer. Alle Methoden sind blockierend (JPA) und müssen daher
 * außerhalb der Event-Loop laufen, siehe {@link FavoriteController}.
 */
@Service
public class FavoriteService {

    public static final int MAX_TEXT_LENGTH = 1000;

    private final FavoriteRepository repository;
    private final int maxPerUser;

    public FavoriteService(FavoriteRepository repository,
                           @Value("${application.favorites.max-per-user:50}") int maxPerUser) {
        this.repository = repository;
        this.maxPerUser = maxPerUser;
    }

    @Transactional(readOnly = true)
    public List<Favorite> list(String userId) {
        return repository.findByUserIdOrderByLastUsedAtDescIdDesc(userId);
    }

    /**
     * Legt einen Favoriten an. Ist der Text für diesen Benutzer schon vorhanden,
     * wird der bestehende Eintrag zurückgegeben (kein Duplikat).
     */
    @Transactional
    public Favorite add(String userId, String rawText) {
        String text = rawText == null ? "" : rawText.strip();
        if (text.isEmpty() || text.length() > MAX_TEXT_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Der Text muss zwischen 1 und " + MAX_TEXT_LENGTH + " Zeichen lang sein.");
        }
        return repository.findByUserIdAndText(userId, text).orElseGet(() -> {
            if (repository.countByUserId(userId) >= maxPerUser) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Es sind höchstens " + maxPerUser + " Favoriten möglich.");
            }
            return repository.save(new Favorite(userId, text));
        });
    }

    @Transactional
    public void delete(String userId, Long id) {
        if (repository.deleteByIdAndUserId(id, userId) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Favorit nicht gefunden.");
        }
    }

    @Transactional
    public void markUsed(String userId, Long id) {
        Favorite favorite = repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Favorit nicht gefunden."));
        favorite.setLastUsedAt(Instant.now());
    }
}
