package com.urlshortener.repository;

import com.urlshortener.domain.Url;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UrlRepository extends JpaRepository<Url, UUID> {

    Optional<Url> findByShortCodeAndActiveTrue(String shortCode);

    boolean existsByShortCode(String shortCode);

    @Modifying
    @Query("UPDATE Url u SET u.active = false WHERE u.expiresAt IS NOT NULL AND u.expiresAt < :now AND u.active = true")
    int deactivateExpiredUrls(Instant now);

    @Modifying
    @Query("UPDATE Url u SET u.clickCount = u.clickCount + :increment WHERE u.shortCode = :shortCode")
    int incrementClickCount(String shortCode, long increment);

    @Query("SELECT u FROM Url u WHERE u.active = true AND u.expiresAt IS NOT NULL AND u.expiresAt < :threshold")
    List<Url> findRecentlyExpired(Instant threshold);
}
