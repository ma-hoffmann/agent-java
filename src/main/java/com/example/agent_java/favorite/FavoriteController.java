package com.example.agent_java.favorite;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import com.example.agent_java.messung.Messpunkt;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.concurrent.Callable;

@Slf4j
@RestController
@RequestMapping("/api/v1/favorites")
@RequiredArgsConstructor
public class FavoriteController {

    public record FavoriteRequest(@NotBlank @Size(max = FavoriteService.MAX_TEXT_LENGTH) String text) {
    }

    public record FavoriteDto(Long id, String text) {
        static FavoriteDto of(Favorite favorite) {
            return new FavoriteDto(favorite.getId(), favorite.getText());
        }
    }

    private final FavoriteService service;
    private final UserIdResolver userIdResolver;

    @GetMapping
    public Mono<List<FavoriteDto>> list() {
        return blocking("favorites.liste", userId -> service.list(userId).stream().map(FavoriteDto::of).toList());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<FavoriteDto> add(@Valid @RequestBody FavoriteRequest request) {
        return blocking("favorites.anlegen", userId -> FavoriteDto.of(service.add(userId, request.text())));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable Long id) {
        return blocking("favorites.loeschen", userId -> {
            service.delete(userId, id);
            return null;
        });
    }

    @PostMapping("/{id}/used")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> used(@PathVariable Long id) {
        return blocking("favorites.als-benutzt-markieren", userId -> {
            service.markUsed(userId, id);
            return null;
        });
    }

    /**
     * JPA blockiert, daher auf dem Scheduler für blockierende Aufgaben ausführen. Der Messpunkt umfasst die
     * Arbeit selbst (Datenbankzugriff), nicht die Wartezeit auf einen freien Thread. Geloggt wird nur der
     * Name des Messpunkts, nie die Benutzer-ID oder der Text des Favoriten.
     */
    private <T> Mono<T> blocking(String messpunktName, java.util.function.Function<String, T> work) {
        return userIdResolver.currentUserId()
                .flatMap(userId -> Mono.fromCallable((Callable<T>) () -> {
                    Messpunkt messpunkt = Messpunkt.start(log, messpunktName, "");
                    try {
                        T result = work.apply(userId);
                        messpunkt.ende("ok");
                        return result;
                    } catch (RuntimeException e) {
                        messpunkt.fehler(e);
                        throw e;
                    }
                }).subscribeOn(Schedulers.boundedElastic()));
    }
}
