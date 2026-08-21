package com.urlshortener;

import com.urlshortener.analytics.ClickEventPublisher;
import com.urlshortener.controller.UrlRedirectController;
import com.urlshortener.exception.GlobalExceptionHandler;
import com.urlshortener.exception.UrlNotFoundException;
import com.urlshortener.metrics.UrlMetrics;
import com.urlshortener.ratelimiter.SlidingWindowRateLimiter;
import com.urlshortener.service.UrlService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(UrlRedirectController.class)
@Import(GlobalExceptionHandler.class)
class UrlRedirectControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UrlService urlService;

    @MockitoBean
    private ClickEventPublisher clickEventPublisher;

    @MockitoBean
    private UrlMetrics urlMetrics;

    @MockitoBean
    private SlidingWindowRateLimiter slidingWindowRateLimiter;

    @BeforeEach
    void setUp() {
        lenient().when(slidingWindowRateLimiter.tryAcquire(anyString(), anyInt(), any())).thenReturn(true);
    }

    @Test
    void redirect_returns302WithLocation() throws Exception {
        when(urlService.resolveUrl("abc123"))
                .thenReturn(new UrlService.ResolvedUrl("https://example.com", "shard-0"));

        mockMvc.perform(get("/abc123"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com"));

        verify(clickEventPublisher).publish(any());
        verify(urlMetrics).recordRedirect();
    }

    @Test
    void redirect_notFound_returns404() throws Exception {
        when(urlService.resolveUrl("missing"))
                .thenThrow(new UrlNotFoundException("missing"));

        mockMvc.perform(get("/missing"))
                .andExpect(status().isNotFound());
    }

    @Test
    void redirect_shortCodeOnly() throws Exception {
        when(urlService.resolveUrl("xY7"))
                .thenReturn(new UrlService.ResolvedUrl("https://other.com", "shard-1"));

        mockMvc.perform(get("/xY7"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://other.com"));
    }

    @Test
    void redirect_longCode() throws Exception {
        when(urlService.resolveUrl("abcdefghijklmnop"))
                .thenReturn(new UrlService.ResolvedUrl("https://long.com", "shard-2"));

        mockMvc.perform(get("/abcdefghijklmnop"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://long.com"));
    }

    @Test
    void redirect_withForwardedFor() throws Exception {
        when(urlService.resolveUrl("abc"))
                .thenReturn(new UrlService.ResolvedUrl("https://example.com", "shard-0"));

        mockMvc.perform(get("/abc")
                        .header("X-Forwarded-For", "10.0.0.1"))
                .andExpect(status().isFound());

        verify(clickEventPublisher).publish(any());
    }
}
