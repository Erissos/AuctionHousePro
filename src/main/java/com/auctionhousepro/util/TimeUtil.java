package com.auctionhousepro.util;

import java.time.Duration;

public final class TimeUtil {
    private TimeUtil() {
    }

    public static String format(Duration duration) {
        long seconds = Math.max(0L, duration.toSeconds());
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        long remainingSeconds = seconds % 60;

        StringBuilder builder = new StringBuilder();
        if (days > 0) {
            builder.append(days).append("d ");
        }
        if (hours > 0 || days > 0) {
            builder.append(hours).append("h ");
        }
        if (minutes > 0 || hours > 0 || days > 0) {
            builder.append(minutes).append("m ");
        }
        builder.append(remainingSeconds).append("s");
        return builder.toString().trim();
    }
}
