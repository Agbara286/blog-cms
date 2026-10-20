import { MarkdownManager } from "@tiptap/markdown";
import { describe, expect, it } from "vitest";
import { clampHeadingLevels } from "@/lib/markdown-import";
import type { ContentDoc } from "@/types/content";
import { editorExtensions } from "./extensions";

// editor.markdown is a MarkdownManager built from the editor's extensions; this runs the installed version directly.
const markdown = new MarkdownManager({ extensions: editorExtensions });

describe("Markdown import with the editor's extensions", () => {
  it("parses Markdown into the Tiptap JSON the editor stores", () => {
    const doc = markdown.parse(
      "# Title\n\nSome **bold** and a [link](https://atomity.de).\n\n- one\n- two\n\n```ts\nconst a = 1;\n```\n",
    ) as ContentDoc;

    expect(doc.type).toBe("doc");
    expect(doc.content?.map((node) => node.type)).toEqual(["heading", "paragraph", "bulletList", "codeBlock"]);
    const marks = doc.content?.[1].content?.flatMap((node) => node.marks?.map((mark) => mark.type) ?? []);
    expect(marks).toEqual(expect.arrayContaining(["bold", "link"]));
  });

  it("keeps the file's heading level, which is why the import clamps it", () => {
    const doc = markdown.parse("# One\n\n#### Four\n") as ContentDoc;
    expect(doc.content?.map((node) => node.attrs?.level)).toEqual([1, 4]);
    expect(clampHeadingLevels(doc).content?.map((node) => node.attrs?.level)).toEqual([2, 3]);
  });
});
