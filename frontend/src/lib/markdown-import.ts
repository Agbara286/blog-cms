import type { ContentNode } from "@/types/content";

export const MARKDOWN_EXTENSIONS = [".md", ".markdown"];
export const MARKDOWN_MAX_BYTES = 1024 * 1024;

/** The editor only supports H2 and H3. */
const MIN_HEADING_LEVEL = 2;
const MAX_HEADING_LEVEL = 3;

const FRONT_MATTER = /^---[ \t]*\r?\n[\s\S]*?\r?\n---[ \t]*(?:\r?\n|$)/;

/** Returns an error message for the user, or null if the file is OK. */
export function validateMarkdownFile(file: File): string | null {
  const name = file.name.toLowerCase();
  if (!MARKDOWN_EXTENSIONS.some((ext) => name.endsWith(ext))) {
    return `"${file.name}" isn't a Markdown file. Choose a .md or .markdown file.`;
  }
  // .md files come through as text/markdown, text/plain or with no type at all, depending on the OS.
  if (file.type && !file.type.startsWith("text/")) {
    return `"${file.name}" doesn't look like a text file.`;
  }
  if (file.size === 0) return `"${file.name}" is empty.`;
  if (file.size > MARKDOWN_MAX_BYTES) return `"${file.name}" is larger than 1 MB.`;
  return null;
}

/** Reads the file and returns the post body, without BOM and front matter. */
export async function readMarkdownFile(file: File): Promise<string> {
  let text: string;
  try {
    text = await file.text();
  } catch {
    throw new Error(`Couldn't read "${file.name}". Try choosing the file again.`);
  }
  // Real text never contains NUL, so this is a binary file renamed to .md.
  if (text.includes("\0")) throw new Error(`"${file.name}" doesn't look like a text file.`);

  const body = stripFrontMatter(text.replace(/^\uFEFF/, ""));
  if (!body.trim()) throw new Error(`"${file.name}" has no content to import.`);
  return body;
}

export function stripFrontMatter(markdown: string): string {
  return markdown.replace(FRONT_MATTER, "");
}

/** H1 becomes H2 and H4-H6 become H3. */
export function clampHeadingLevels<T extends ContentNode>(node: T): T {
  const level = node.attrs?.level;
  const attrs =
    node.type === "heading" && typeof level === "number"
      ? { ...node.attrs, level: Math.min(Math.max(level, MIN_HEADING_LEVEL), MAX_HEADING_LEVEL) }
      : node.attrs;
  return {
    ...node,
    ...(attrs && { attrs }),
    ...(node.content && { content: node.content.map((child) => clampHeadingLevels(child)) }),
  };
}
