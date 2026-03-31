package com.auctionhousepro.util;

import java.time.Duration;
import java.util.Locale;

public final class DurationParser {
    private DurationParser() {
    }

    public static Duration parse(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        String normalized = input.trim().toLowerCase(Locale.ROOT);
        if (normalized.chars().allMatch(Character::isDigit)) {
            return Duration.ofMinutes(Long.parseLong(normalized));
        }

        long totalMinutes = 0L;
        StringBuilder number = new StringBuilder();
        for (char character : normalized.toCharArray()) {
            if (Character.isDigit(character)) {
                number.append(character);
                continue;
            }
            if (number.isEmpty()) {
                return null;
            }
            long value = Long.parseLong(number.toString());
            number.setLength(0);
            switch (character) {
                case 'm' -> totalMinutes += value;
                case 'h' -> totalMinutes += value * 60L;
                case 'd' -> totalMinutes += value * 1440L;
                default -> {
                    return null;
                }
            }
        }
        if (!number.isEmpty()) {
            totalMinutes += Long.parseLong(number.toString());
        }
        return totalMinutes > 0L ? Duration.ofMinutes(totalMinutes) : null;
    }
}