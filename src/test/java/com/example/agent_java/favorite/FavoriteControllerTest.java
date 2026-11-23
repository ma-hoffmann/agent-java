package com.example.agent_java.favorite;

import com.example.agent_java.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
class FavoriteControllerTest {

    @Autowired
    WebTestClient client;

    @Autowired
    FavoriteRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void createListAndDelete() {
        client.post().uri("/api/v1/favorites")
                .bodyValue(Map.of("text", "Wer hat heute gebucht?"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.text").isEqualTo("Wer hat heute gebucht?");
        Long id = repository.findAll().getFirst().getId();

        client.get().uri("/api/v1/favorites")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].text").isEqualTo("Wer hat heute gebucht?");

        client.delete().uri("/api/v1/favorites/{id}", id)
                .exchange()
                .expectStatus().isNoContent();

        client.get().uri("/api/v1/favorites")
                .exchange()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(0);
    }

    @Test
    void blankTextIsRejectedAsProblemJson() {
        client.post().uri("/api/v1/favorites")
                .bodyValue(Map.of("text", "  "))
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.errors[0].field").isEqualTo("text");
    }

    @Test
    void textIsLimitedTo1000Characters() {
        client.post().uri("/api/v1/favorites")
                .bodyValue(Map.of("text", "x".repeat(1000)))
                .exchange()
                .expectStatus().isCreated();
        client.post().uri("/api/v1/favorites")
                .bodyValue(Map.of("text", "x".repeat(1001)))
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void deletingUnknownFavoriteReturns404AsProblemJson() {
        client.delete().uri("/api/v1/favorites/{id}", 999_999)
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(404)
                .jsonPath("$.detail").isEqualTo("Favorit nicht gefunden.");
    }

    @Test
    void oldPathIsGone() {
        client.get().uri("/api/favorites").exchange().expectStatus().isNotFound();
    }
}
