/** An imported .md file linked to a post. */
export interface MarkdownSourceRef {
  id: string;
  fileName: string;
  sizeBytes: number;
  url: string;
}
