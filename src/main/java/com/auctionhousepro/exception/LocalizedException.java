package com.auctionhousepro.exception;

import java.util.Collections;
import java.util.Map;

public final class LocalizedException extends RuntimeException {
    private final String messageKey;
    private final Map<String, String> placeholders;

    public LocalizedException(String messageKey) {
        this(messageKey, Map.of());
    }

    public LocalizedException(String messageKey, Map<String, String> placeholders) {
        super(messageKey);
        this.messageKey = messageKey;
        this.placeholders = Collections.unmodifiableMap(placeholders);
    }

    public String messageKey() {
        return messageKey;
    }

    public Map<String, String> placeholders() {
        return placeholders;
    }
}