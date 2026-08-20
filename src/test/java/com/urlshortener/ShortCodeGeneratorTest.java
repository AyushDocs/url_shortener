package com.urlshortener;

import com.urlshortener.generator.ShortCodeGenerator;
import com.urlshortener.distributed.persistence.ShardedUrlRepository;
import com.urlshortener.distributed.hashring.ConsistentHashRing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ShortCodeGeneratorTest {

    @Mock
    private ShardedUrlRepository shardedUrlRepository;

    private ShortCodeGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new ShortCodeGenerator(6, shardedUrlRepository);
    }

    @Test
    void generateRandom_returnsCorrectLength() {
        String code = generator.generateRandom();
        assertEquals(6, code.length());
    }

    @Test
    void generateRandom_usesValidCharacters() {
        String validChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        for (int i = 0; i < 100; i++) {
            String code = generator.generateRandom();
            for (char c : code.toCharArray()) {
                assertTrue(validChars.indexOf(c) >= 0, "Invalid char: " + c);
            }
        }
    }

    @Test
    void encodeBase62_zero_returnsFirstChar() {
        String result = generator.encodeBase62(0);
        assertEquals("A", result);
    }

    @Test
    void encodeBase62_deterministic() {
        String a = generator.encodeBase62(125);
        String b = generator.encodeBase62(125);
        assertEquals(a, b);
    }

    @Test
    void encodeBase62_knownValues() {
        assertEquals("B", generator.encodeBase62(1));
        assertEquals("C", generator.encodeBase62(2));
    }

    @Test
    void generateUnique_noExistingCollisions() {
        when(shardedUrlRepository.existsByShortCode(anyString())).thenReturn(false);
        String code = generator.generateUnique();
        assertNotNull(code);
        assertEquals(6, code.length());
    }

    @Test
    void isValidCode_valid() {
        assertTrue(generator.isValidCode("aZ91xK"));
        assertTrue(generator.isValidCode("abc"));
    }

    @Test
    void isValidCode_invalid() {
        assertFalse(generator.isValidCode(null));
        assertFalse(generator.isValidCode(""));
        assertFalse(generator.isValidCode("abc-def"));
        assertFalse(generator.isValidCode("has space"));
    }

    @Test
    void consistentHashRing_distributesAcrossShards() {
        List<String> shards = List.of("shard-0", "shard-1", "shard-2");
        ConsistentHashRing<String> ring = new ConsistentHashRing<>(shards, 150);

        int[] distribution = new int[3];
        for (int i = 0; i < 10000; i++) {
            String code = generator.generateRandom();
            String target = ring.getNode(code);
            int idx = shards.indexOf(target);
            distribution[idx]++;
        }

        for (int i = 0; i < 3; i++) {
            assertTrue(distribution[i] > 2000,
                    "Shard " + i + " got only " + distribution[i] + " keys — poor distribution");
        }
    }
}
