package com.urlshortener.service;

import com.urlshortener.analytics.ClickEvent;
import com.urlshortener.analytics.ClickEventPublisher;
import org.springframework.stereotype.Service;

@Service
public class AnalyticsService {

    private final ClickEventPublisher clickEventPublisher;

    public AnalyticsService(ClickEventPublisher clickEventPublisher) {
        this.clickEventPublisher = clickEventPublisher;
    }

    public void trackClick(String shortCode) {
        clickEventPublisher.publish(new ClickEvent(shortCode));
    }
}
