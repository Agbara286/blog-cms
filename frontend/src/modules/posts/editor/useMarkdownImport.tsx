"use client";

import { useRef, useState } from "react";
import type { Editor } from "@tiptap/react";
import { ConfirmDialog } from "@/components/ui/ConfirmDialog";
import { useToast } from "@/components/ui/Toast";
import { isDocEmpty } from "@/lib/content";
import { clampHeadingLevels, MARKDOWN_EXTENSIONS, readMarkdownFile, validateMarkdownFile } from "@/lib/markdown-import";
import type { ContentDoc } from "@/types/content";

interface ParsedImport {
  fileName: string;
  doc: ContentDoc;
}

/** Replaces the post body with an imported .md file (asks first if the post isn't empty). */
export function useMarkdownImport(editor: Editor) {
  const toast = useToast();
  const inputRef = useRef<HTMLInputElement>(null);
  const [pending, setPending] = useState<ParsedImport | null>(null);

  const apply = ({ fileName, doc }: ParsedImport) => {
    editor.chain().focus().setContent(doc).run();
    toast.success(`Imported "${fileName}".`);
  };

  const onFileChange = async (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    // Allows picking the same file again.
    event.target.value = "";
    if (!file) return;

    const invalid = validateMarkdownFile(file);
    if (invalid) {
      toast.error(invalid);
      return;
    }

    let parsed: ParsedImport;
    try {
      const markdown = await readMarkdownFile(file);
      if (!editor.markdown) throw new Error("The editor can't read Markdown.");
      let doc: ContentDoc;
      try {
        doc = clampHeadingLevels(editor.markdown.parse(markdown) as ContentDoc);
      } catch {
        throw new Error(`Couldn't convert "${file.name}" from Markdown.`);
      }
      if (isDocEmpty(doc)) throw new Error(`"${file.name}" has no content to import.`);
      parsed = { fileName: file.name, doc };
    } catch (error) {
      toast.error(error instanceof Error ? error.message : `Couldn't import "${file.name}".`);
      return;
    }

    if (isDocEmpty(editor.getJSON() as ContentDoc)) apply(parsed);
    else setPending(parsed);
  };

  const ui = (
    <>
      <input
        ref={inputRef}
        type="file"
        accept={[...MARKDOWN_EXTENSIONS, "text/markdown"].join(",")}
        className="hidden"
        tabIndex={-1}
        aria-hidden
        onChange={onFileChange}
      />
      {pending && (
        <ConfirmDialog
          title="Replace post content?"
          message={
            <>
              The content of <strong>{pending.fileName}</strong> will replace everything in the editor. You can undo
              this with Ctrl+Z.
            </>
          }
          confirmLabel="Replace content"
          onConfirm={() => apply(pending)}
          onClose={() => setPending(null)}
        />
      )}
    </>
  );

  return { openFilePicker: () => inputRef.current?.click(), ui };
}
