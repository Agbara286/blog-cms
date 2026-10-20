package io.atomity.blogcms.shared;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FilenamesTest {

    private static final String EMOJI = new String(Character.toChars(0x1F600));

    @Test
    void keepsOnlyTheBaseName() {
        assertThat(Filenames.clean("C:\\drafts\\..\\post.md", "x")).isEqualTo("post.md");
        assertThat(Filenames.clean("../../etc/cover.png", "x")).isEqualTo("cover.png");
        assertThat(Filenames.clean("  ", "image.png")).isEqualTo("image.png");
        assertThat(Filenames.clean(null, "post.md")).isEqualTo("post.md");
    }

    @Test
    void shortensLongNamesFromTheStartAndKeepsTheExtension() {
        String name = Filenames.clean("a".repeat(300) + ".md", "x");
        assertThat(name).hasSize(Filenames.MAX_LENGTH).endsWith(".md");
    }

    @Test
    void neverCutsAnEmojiInHalf() {
        // 200 emojis are 400 UTF-16 units; with "x.md" the plain cut would land on the second half of one.
        for (String suffix : new String[] {".md", "x.md"}) {
            String name = Filenames.clean(EMOJI.repeat(200) + suffix, "x");
            assertThat(Character.isLowSurrogate(name.charAt(0))).isFalse();
            assertThat(name.length()).isLessThanOrEqualTo(Filenames.MAX_LENGTH);
            assertThat(name).endsWith(suffix).startsWith(EMOJI);
        }
    }
}
