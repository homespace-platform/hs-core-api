package com.hs.listing.service;

import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.function.Predicate;

/** Builds SEO slugs "<slugified-title>-<8 random chars>". The slug is assigned once and never regenerated. */
public final class ListingSlugGenerator {
    static final int MAX_TITLE_CHARS = 80;
    static final int CODE_LENGTH = 8;
    static final int MAX_ATTEMPTS = 10;
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private ListingSlugGenerator() {}

    public static String slugify(String title) {
        String s = title == null ? "" : Normalizer.normalize(title.toLowerCase(java.util.Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").replace('đ', 'd');
        s = s.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (s.length() > MAX_TITLE_CHARS) {
            int cut = s.lastIndexOf('-', MAX_TITLE_CHARS);
            s = cut > 0 ? s.substring(0, cut) : s.substring(0, MAX_TITLE_CHARS); // cut on a word boundary when possible
        }
        return s.isEmpty() ? "tin-dang" : s;
    }

    /** Retries with a fresh code while {@code taken} reports a collision; throws after {@link #MAX_ATTEMPTS}. */
    public static String generate(String title, Predicate<String> taken) {
        String base = slugify(title);
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            String slug = base + "-" + randomCode();
            if (!taken.test(slug)) return slug;
        }
        throw new IllegalStateException("Could not generate a unique listing slug");
    }

    private static String randomCode() {
        var sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return sb.toString();
    }
}
