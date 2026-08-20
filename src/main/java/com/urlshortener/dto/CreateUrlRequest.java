package com.urlshortener.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;

public record CreateUrlRequest(
        @NotBlank(message = "URL must not be blank")
        @Size(max = 2048, message = "URL must not exceed 2048 characters")
        @URL(message = "Must be a valid URL")
        String url,

        @Size(min = 1, max = 16, message = "Custom code must be between 1 and 16 characters")
        String customCode,

        Integer ttlSeconds
) {
    public CreateUrlRequest {
    }

    public CreateUrlRequest(String url) {
        this(url, null, null);
    }
}
