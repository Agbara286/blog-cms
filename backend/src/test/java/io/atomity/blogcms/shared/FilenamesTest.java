package io.atomity.blogcms.markdown.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownSourceServiceTest {

    private static final String EMOJI = new String(Character.toChars(0x1F600));

    @Test
    void keepsOnlyTheBaseName() {
        assertThat(MarkdownSourceService.cleanFilename("C:\\drafts\\..\\post.md")).isEqualTo("post.md");
        assertThat(MarkdownSourceService.cleanFilename("  ")).isEqualTo("post.md");
        assertThat(MarkdownSourceService.cleanFilename(null)).isEqualTo("post.md");
    }

    @Test
    void shortensLongNamesFromTheStartAndKeepsTheExtension() {
        String name = MarkdownSourceService.cleanFilename("a".repeat(300) + ".md");
        assertThat(name).hasSize(255).endsWith(".md");
    }

    @Test
    void neverCutsAnEmojiInHalf() {
        // 200 emojis are 400 UTF-16 units; with "x.md" the plain cut would land on the second half of one.
        for (String suffix : new String[] {".md", "x.md"}) {
            String name = MarkdownSourceService.cleanFilename(EMOJI.repeat(200) + suffix);
            assertThat(Character.isLowSurrogate(name.charAt(0))).isFalse();
            assertThat(name.length()).isLessThanOrEqualTo(255);
            assertThat(name).endsWith(suffix).startsWith(EMOJI);
        }
    }
}
