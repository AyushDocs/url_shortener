package com.urlshortener;

import com.urlshortener.controller.UrlManagementController;
import com.urlshortener.dto.CreateUrlRequest;
import com.urlshortener.dto.CreateUrlResponse;
import com.urlshortener.dto.UrlDetailResponse;
import com.urlshortener.exception.GlobalExceptionHandler;
import com.urlshortener.exception.UrlNotFoundException;
import com.urlshortener.metrics.UrlMetrics;
import com.urlshortener.ratelimiter.SlidingWindowRateLimiter;
import com.urlshortener.service.UrlService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(UrlManagementController.class)
@Import(GlobalExceptionHandler.class)
class UrlManagementControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UrlService urlService;

    @MockitoBean
    private UrlMetrics urlMetrics;

    @MockitoBean
    private SlidingWindowRateLimiter slidingWindowRateLimiter;

    @BeforeEach
    void setUp() {
        lenient().when(slidingWindowRateLimiter.tryAcquire(anyString(), anyInt(), any())).thenReturn(true);
    }

    @Test
    void createUrl_returns201WithResponse() throws Exception {
        CreateUrlRequest request = new CreateUrlRequest("https://example.com");
        CreateUrlResponse response = new CreateUrlResponse(
                "abc123", "https://sho.rt/abc123", "https://example.com",
                Instant.now(), null);
        when(urlService.createUrl(any(CreateUrlRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shortCode").value("abc123"))
                .andExpect(jsonPath("$.shortUrl").value("https://sho.rt/abc123"))
                .andExpect(jsonPath("$.originalUrl").value("https://example.com"));

        verify(urlMetrics).recordUrlCreated();
    }

    @Test
    void createUrl_blankUrl_returns400() throws Exception {
        CreateUrlRequest request = new CreateUrlRequest("   ");

        mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createUrl_invalidUrl_returns400() throws Exception {
        String json = "{\"url\": \"not-a-url\"}";

        mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createUrl_missingUrl_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getUrlDetail_returns200() throws Exception {
        UrlDetailResponse response = new UrlDetailResponse(
                "abc123", "https://sho.rt/abc123", "https://example.com",
                Instant.now(), null, true, 42);
        when(urlService.getUrlDetail("abc123")).thenReturn(response);

        mockMvc.perform(get("/api/v1/urls/abc123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shortCode").value("abc123"))
                .andExpect(jsonPath("$.clickCount").value(42))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void getUrlDetail_notFound_returns404() throws Exception {
        when(urlService.getUrlDetail("missing")).thenThrow(new UrlNotFoundException("missing"));

        mockMvc.perform(get("/api/v1/urls/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(containsString("missing")));
    }

    @Test
    void deleteUrl_returns204() throws Exception {
        mockMvc.perform(delete("/api/v1/urls/abc123"))
                .andExpect(status().isNoContent());

        verify(urlService).deleteUrl("abc123");
    }

    @Test
    void deleteUrl_notFound_returns404() throws Exception {
        doThrow(new UrlNotFoundException("missing")).when(urlService).deleteUrl("missing");

        mockMvc.perform(delete("/api/v1/urls/missing"))
                .andExpect(status().isNotFound());
    }
}
