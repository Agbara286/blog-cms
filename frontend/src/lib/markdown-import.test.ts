import { describe, expect, it } from "vitest";
import type { ContentDoc } from "@/types/content";
import {
  clampHeadingLevels,
  MARKDOWN_MAX_BYTES,
  readMarkdownFile,
  stripFrontMatter,
  validateMarkdownFile,
} from "./markdown-import";

function mdFile(content: string, name = "post.md", type = "text/markdown"): File {
  return new File([content], name, { type });
}

describe("validateMarkdownFile", () => {
  it("accepts .md and .markdown files, whatever the case", () => {
    expect(validateMarkdownFile(mdFile("# Hi"))).toBeNull();
    expect(validateMarkdownFile(mdFile("# Hi", "Notes.MARKDOWN"))).toBeNull();
  });

  it("accepts the MIME types browsers report for Markdown, including none", () => {
    expect(validateMarkdownFile(mdFile("# Hi", "post.md", "text/plain"))).toBeNull();
    expect(validateMarkdownFile(mdFile("# Hi", "post.md", ""))).toBeNull();
  });

  it("rejects other extensions", () => {
    expect(validateMarkdownFile(mdFile("# Hi", "post.txt"))).toMatch(/isn't a Markdown file/);
    expect(validateMarkdownFile(mdFile("# Hi", "post.md.pdf"))).toMatch(/isn't a Markdown file/);
  });

  it("rejects a non-text file renamed to .md", () => {
    expect(validateMarkdownFile(mdFile("x", "photo.md", "image/png"))).toMatch(/doesn't look like a text file/);
  });

  it("rejects empty and oversized files", () => {
    expect(validateMarkdownFile(mdFile(""))).toMatch(/is empty/);
    expect(validateMarkdownFile(mdFile("a".repeat(MARKDOWN_MAX_BYTES + 1)))).toMatch(/larger than 1 MB/);
  });
});

describe("stripFrontMatter", () => {
  it("removes a leading front matter block", () => {
    expect(stripFrontMatter("---\ntitle: Hello\ntags: [a, b]\n---\n# Body\n")).toBe("# Body\n");
  });

  it("handles Windows line endings", () => {
    expect(stripFrontMatter("---\r\ntitle: Hello\r\n---\r\nBody")).toBe("Body");
  });

  it("keeps going past an indented --- inside a block scalar", () => {
    const markdown = "---\ntitle: Hello\ndescription: |\n  line one\n  ---\n  line two\n---\n# Body\n";
    expect(stripFrontMatter(markdown)).toBe("# Body\n");
  });

  it("accepts ... as the closing line and an empty block", () => {
    expect(stripFrontMatter("---\ntitle: Hello\n...\n# Body\n")).toBe("# Body\n");
    expect(stripFrontMatter("---\n---\n# Body\n")).toBe("# Body\n");
  });

  it("leaves an unclosed block alone", () => {
    const markdown = "---\ntitle: Hello\n# Body\n";
    expect(stripFrontMatter(markdown)).toBe(markdown);
  });

  it("leaves a horizontal rule in the middle of the post alone", () => {
    const markdown = "Intro\n\n---\n\nMore text\n\n---\n";
    expect(stripFrontMatter(markdown)).toBe(markdown);
  });
});

describe("readMarkdownFile", () => {
  it("returns the body without byte-order mark or front matter", async () => {
    await expect(readMarkdownFile(mdFile("\uFEFF---\ntitle: x\n---\nHello"))).resolves.toBe("Hello");
  });

  it("rejects binary content", async () => {
    await expect(readMarkdownFile(mdFile("abc\0def"))).rejects.toThrow(/doesn't look like a text file/);
  });

  it("rejects a file that only holds front matter", async () => {
    await expect(readMarkdownFile(mdFile("---\ntitle: x\n---\n  \n"))).rejects.toThrow(/no content to import/);
  });
});

describe("clampHeadingLevels", () => {
  const heading = (level: number) => ({ type: "heading", attrs: { level }, content: [{ type: "text", text: `h${level}` }] });

  it("maps H1 to H2 and H4–H6 to H3, keeping H2 and H3", () => {
    const doc: ContentDoc = { type: "doc", content: [1, 2, 3, 4, 5, 6].map(heading) };
    const levels = clampHeadingLevels(doc).content?.map((node) => node.attrs?.level);
    expect(levels).toEqual([2, 2, 3, 3, 3, 3]);
  });

  it("reaches headings nested inside other blocks and leaves other nodes unchanged", () => {
    const doc: ContentDoc = {
      type: "doc",
      content: [
        { type: "blockquote", content: [heading(1)] },
        { type: "paragraph", content: [{ type: "text", text: "plain", marks: [{ type: "bold" }] }] },
      ],
    };
    const result = clampHeadingLevels(doc);
    expect(result.content?.[0].content?.[0].attrs?.level).toBe(2);
    expect(result.content?.[1]).toEqual(doc.content?.[1]);
  });

  it("does not mutate the input", () => {
    const doc: ContentDoc = { type: "doc", content: [heading(1)] };
    clampHeadingLevels(doc);
    expect(doc.content?.[0].attrs?.level).toBe(1);
  });
});
