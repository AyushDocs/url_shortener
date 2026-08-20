package com.urlshortener.scheduler;

import com.urlshortener.repository.UrlRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Component
public class ExpiredUrlCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(ExpiredUrlCleanupScheduler.class);

    private final UrlRepository urlRepository;

    public ExpiredUrlCleanupScheduler(UrlRepository urlRepository) {
        this.urlRepository = urlRepository;
    }

    @Scheduled(fixedRate = 300_000)
    @Transactional
    public void deactivateExpiredUrls() {
        int deactivated = urlRepository.deactivateExpiredUrls(Instant.now());
        if (deactivated > 0) {
            log.info("Deactivated {} expired URLs", deactivated);
        }
    }

    @Scheduled(fixedRate = 600_000)
    @Transactional
    public void flushAnalyticsCounts() {
        Instant threshold = Instant.now().minusSeconds(600);
        var recentlyExpired = urlRepository.findRecentlyExpired(threshold);
        for (var url : recentlyExpired) {
            url.setActive(false);
            urlRepository.save(url);
        }
    }
}
