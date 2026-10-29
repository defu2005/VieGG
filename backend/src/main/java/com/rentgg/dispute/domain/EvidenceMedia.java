package com.rentgg.dispute.domain;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Detects the type of an uploaded evidence file from its magic bytes (never from the client's claim). */
public final class EvidenceMedia {

    public static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;
    public static final int MAX_VIDEO_BYTES = 20 * 1024 * 1024;

    /** ISO base media brands of real videos; excludes HEIC/AVIF images and M4A audio that share the format. */
    private static final Set<String> VIDEO_BRAND_PREFIXES = Set.of("iso", "mp4", "avc", "M4V", "3gp", "3g2", "dash",
            "MSNV", "mmp4");

    private EvidenceMedia() {}

    public static String detect(byte[] d) {
        if (d.length >= 3 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (d.length >= 8 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G'
                && d[4] == 0x0D && d[5] == 0x0A && d[6] == 0x1A && d[7] == 0x0A) {
            return "image/png";
        }
        if (d.length >= 12 && d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F'
                && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P') {
            return "image/webp";
        }
        if (d.length >= 4 && (d[0] & 0xFF) == 0x1A && (d[1] & 0xFF) == 0x45 && (d[2] & 0xFF) == 0xDF
                && (d[3] & 0xFF) == 0xA3) {
            return "video/webm";
        }
        if (d.length >= 12 && d[4] == 'f' && d[5] == 't' && d[6] == 'y' && d[7] == 'p') {
            String brand = new String(d, 8, 4, StandardCharsets.US_ASCII);
            if (brand.equals("qt  ")) {
                return "video/quicktime";
            }
            if (VIDEO_BRAND_PREFIXES.stream().anyMatch(brand::startsWith)) {
                return "video/mp4";
            }
        }
        return null;
    }

    public static boolean isVideo(String contentType) {
        return contentType.startsWith("video/");
    }
}
