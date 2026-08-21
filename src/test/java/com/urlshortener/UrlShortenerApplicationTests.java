package com.urlshortener;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@EnabledIfSystemProperty(named = "runIntegrationTests", matches = "true")
class UrlShortenerApplicationTests {

    @Test
    void contextLoads() {
    }
}
