package com.example.agent_java.favorite;

import com.example.agent_java.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "application.favorites.max-per-user=3")
@Import(TestcontainersConfiguration.class)
class FavoriteServiceTest {

    @Autowired
    FavoriteService service;

    @Autowired
    FavoriteRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void addAndListTrimsText() {
        service.add("anna", "  Welche Courts sind morgen frei?  ");

        assertThat(service.list("anna")).extracting(Favorite::getText)
                .containsExactly("Welche Courts sind morgen frei?");
    }

    @Test
    void duplicateReturnsExistingEntry() {
        Favorite first = service.add("anna", "Freie Courts heute");
        Favorite second = service.add("anna", "Freie Courts heute");

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(service.list("anna")).hasSize(1);
    }

    @Test
    void usersAreSeparated() {
        Favorite annas = service.add("anna", "Nur für Anna");
        service.add("ben", "Nur für Ben");

        assertThat(service.list("anna")).extracting(Favorite::getText).containsExactly("Nur für Anna");
        assertThatThrownBy(() -> service.delete("ben", annas.getId()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThat(service.list("anna")).hasSize(1);
    }

    @Test
    void sameTextForDifferentUsersIsAllowed() {
        service.add("anna", "Gleicher Text");
        service.add("ben", "Gleicher Text");

        assertThat(service.list("anna")).hasSize(1);
        assertThat(service.list("ben")).hasSize(1);
    }

    @Test
    void deleteRemovesFavorite() {
        Favorite favorite = service.add("anna", "Zum Löschen");

        service.delete("anna", favorite.getId());

        assertThat(service.list("anna")).isEmpty();
    }

    @Test
    void limitPerUserIsEnforced() {
        service.add("anna", "1");
        service.add("anna", "2");
        service.add("anna", "3");

        assertThatThrownBy(() -> service.add("anna", "4"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        // Ein anderer Benutzer ist davon nicht betroffen.
        service.add("ben", "1");
    }

    @Test
    void blankAndTooLongTextIsRejected() {
        assertThatThrownBy(() -> service.add("anna", "   "))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.add("anna", "x".repeat(FavoriteService.MAX_TEXT_LENGTH + 1)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void markUsedMovesFavoriteToTop() throws InterruptedException {
        Favorite older = service.add("anna", "Älter");
        Thread.sleep(5);
        service.add("anna", "Neuer");
        Thread.sleep(5);

        service.markUsed("anna", older.getId());

        assertThat(service.list("anna")).extracting(Favorite::getText).containsExactly("Älter", "Neuer");
    }
}
