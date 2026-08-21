package com.urlshortener;

import com.urlshortener.distributed.persistence.ShardedUrlRepository;
import com.urlshortener.distributed.routing.ShardRouter;
import com.urlshortener.distributed.routing.ShardRoutingContext;
import com.urlshortener.domain.Url;
import com.urlshortener.repository.UrlRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ShardedUrlRepositoryTest {

    @Mock
    private UrlRepository urlRepository;

    @Mock
    private ShardRouter shardRouter;

    @InjectMocks
    private ShardedUrlRepository shardedUrlRepository;

    @AfterEach
    void cleanup() {
        ShardRoutingContext.clear();
    }

    @Test
    void save_setsShardContext_thenClears() {
        Url url = new Url("abc", "https://example.com", null);
        when(shardRouter.route("abc")).thenReturn("shard-1");
        when(urlRepository.save(any(Url.class))).thenReturn(url);

        shardedUrlRepository.save(url);

        ArgumentCaptor<Url> captor = ArgumentCaptor.forClass(Url.class);
        verify(urlRepository).save(captor.capture());
        assertEquals("shard-1", captor.getValue().getShardId());
        assertNull(ShardRoutingContext.getShard(), "Context should be cleared after save");
    }

    @Test
    void findByShortCode_routesToCorrectShard() {
        Url url = new Url("abc", "https://example.com", null);
        when(shardRouter.route("abc")).thenReturn("shard-2");
        when(urlRepository.findByShortCodeAndActiveTrue("abc")).thenReturn(Optional.of(url));

        Optional<Url> result = shardedUrlRepository.findByShortCode("abc");

        assertTrue(result.isPresent());
        verify(shardRouter).route("abc");
        assertNull(ShardRoutingContext.getShard(), "Context should be cleared");
    }

    @Test
    void existsByShortCode_usesRouter() {
        when(shardRouter.route("abc")).thenReturn("shard-0");
        when(urlRepository.existsByShortCode("abc")).thenReturn(true);

        assertTrue(shardedUrlRepository.existsByShortCode("abc"));
        verify(urlRepository).existsByShortCode("abc");
    }

    @Test
    void delete_setsInactiveOnCorrectShard() {
        Url url = new Url("abc", "https://example.com", null);
        when(shardRouter.route("abc")).thenReturn("shard-0");
        when(urlRepository.findByShortCodeAndActiveTrue("abc")).thenReturn(Optional.of(url));

        shardedUrlRepository.delete("abc");

        assertFalse(url.isActive());
        verify(urlRepository).save(url);
    }

    @Test
    void deactivateExpiredUrls_iteratesAllShards() {
        when(shardRouter.getAllShardNames()).thenReturn(List.of("shard-0", "shard-1", "shard-2"));
        when(urlRepository.deactivateExpiredUrls(any())).thenReturn(1);

        int total = shardedUrlRepository.deactivateExpiredUrls(java.time.Instant.now());

        assertEquals(3, total);
        verify(urlRepository, times(3)).deactivateExpiredUrls(any());
    }

    @Test
    void contextCleared_onException() {
        when(shardRouter.route("abc")).thenReturn("shard-0");
        when(urlRepository.findByShortCodeAndActiveTrue("abc"))
                .thenThrow(new RuntimeException("DB error"));

        try {
            shardedUrlRepository.findByShortCode("abc");
        } catch (RuntimeException ignored) {}

        assertNull(ShardRoutingContext.getShard(),
                "Context should be cleared even on exception");
    }
}
