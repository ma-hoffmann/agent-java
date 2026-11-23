package com.example.agent_java.favorite;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FavoriteRepository extends JpaRepository<Favorite, Long> {

    List<Favorite> findByUserIdOrderByLastUsedAtDescIdDesc(String userId);

    Optional<Favorite> findByIdAndUserId(Long id, String userId);

    Optional<Favorite> findByUserIdAndText(String userId, String text);

    long countByUserId(String userId);

    long deleteByIdAndUserId(Long id, String userId);
}
