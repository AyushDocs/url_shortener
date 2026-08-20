package com.urlshortener.generator;

import com.urlshortener.domain.Url;
import com.urlshortener.repository.UrlRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

@Component
public class ShortCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(ShortCodeGenerator.class);
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int MAX_RETRIES = 5;

    private final int codeLength;
    private final UrlRepository urlRepository;

    public ShortCodeGenerator(
            @Value("${app.short-code.length:6}") int codeLength,
            UrlRepository urlRepository) {
        this.codeLength = codeLength;
        this.urlRepository = urlRepository;
    }

    public String generateUnique() {
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            String code = generateRandom();
            if (!urlRepository.existsByShortCode(code)) {
                return code;
            }
            log.warn("Collision on attempt {}: shortCode={}", attempt + 1, code);
        }

        log.info("Falling back to deterministic Base62 generation");
        return generateDeterministic();
    }

    public String generateRandom() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        StringBuilder sb = new StringBuilder(codeLength);
        for (int i = 0; i < codeLength; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    private String generateDeterministic() {
        long maxId = 56_800_235_584L;
        long id = ThreadLocalRandom.current().nextLong(maxId);
        return encodeBase62(id);
    }

    public String encodeBase62(long id) {
        if (id == 0) return String.valueOf(ALPHABET.charAt(0));

        StringBuilder sb = new StringBuilder();
        while (id > 0) {
            sb.append(ALPHABET.charAt((int) (id % 62)));
            id /= 62;
        }
        return sb.reverse().toString();
    }

    public boolean isValidCode(String code) {
        if (code == null || code.length() < 1 || code.length() > 16) return false;
        for (char c : code.toCharArray()) {
            if (ALPHABET.indexOf(c) == -1) return false;
        }
        return true;
    }
}
