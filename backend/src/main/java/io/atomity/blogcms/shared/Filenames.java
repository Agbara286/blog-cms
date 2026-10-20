package io.atomity.blogcms.shared;

/** Uploaded file names, cleaned before they are stored as metadata. */
public final class Filenames {

    /** Fits the {@code original_filename VARCHAR(255)} columns. */
    public static final int MAX_LENGTH = 255;

    private Filenames() {
    }

    /**
     * Keeps only the base name (no client paths or control characters). Long names are cut
     * from the start so the extension survives, never between the two halves of a character
     * such as an emoji. Falls back to {@code fallback} when nothing is left.
     */
    public static String clean(String original, String fallback) {
        String name = original == null ? "" : original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").strip();
        if (name.isEmpty()) {
            return fallback;
        }
        if (name.length() <= MAX_LENGTH) {
            return name;
        }
        int start = name.length() - MAX_LENGTH;
        if (Character.isLowSurrogate(name.charAt(start))) {
            start++;
        }
        return name.substring(start);
    }
}
