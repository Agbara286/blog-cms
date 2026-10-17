"use client";

import { FileText, FileUp } from "lucide-react";
import { Spinner } from "@/components/ui/Spinner";
import { formatBytes } from "@/lib/format";
import type { MarkdownSourceRef } from "@/types/markdown";

interface MarkdownSourceBarProps {
  source: MarkdownSourceRef | null;
  uploading: boolean;
  onImport: () => void;
  onRemove: () => void;
}

/** Upload section above the editor: the imported .md file, or a button to import one. */
export function MarkdownSourceBar({ source, uploading, onImport, onRemove }: MarkdownSourceBarProps) {
  if (!source) {
    return (
      <button
        type="button"
        onClick={onImport}
        disabled={uploading}
        className="flex w-full cursor-pointer items-center gap-2 rounded-lg border-[1.5px] border-dashed border-line px-3 py-2.5 text-left text-muted transition-colors hover:border-line-strong hover:bg-subtle hover:text-ink disabled:cursor-wait"
      >
        {uploading ? <Spinner size={14} /> : <FileUp size={15} aria-hidden />}
        <span className="text-[12px] font-semibold">{uploading ? "Importing…" : "Import a Markdown file"}</span>
        <span className="ml-auto text-[11px]">.md or .markdown, up to 1 MB</span>
      </button>
    );
  }

  return (
    <div className="flex items-center gap-2 rounded-lg border border-line bg-card px-3 py-2">
      <FileText size={15} className="shrink-0 text-muted" aria-hidden />
      <a
        href={source.url}
        download={source.fileName}
        title="Download the original file"
        className="min-w-0 truncate text-[12px] font-semibold hover:underline"
      >
        {source.fileName}
      </a>
      <span className="shrink-0 text-[11px] text-muted">{formatBytes(source.sizeBytes)}</span>
      <div className="ml-auto flex shrink-0 gap-1.5">
        <button type="button" className="btn btn-sm btn-secondary" onClick={onImport} disabled={uploading}>
          {uploading && <Spinner size={12} />}
          Replace
        </button>
        <button type="button" className="btn btn-sm btn-ghost text-error-text" onClick={onRemove} disabled={uploading}>
          Remove
        </button>
      </div>
    </div>
  );
}
