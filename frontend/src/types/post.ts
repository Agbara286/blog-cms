import type { AuthorRef } from "./author";
import type { ContentDoc } from "./content";
import type { MarkdownSourceRef } from "./markdown";
import type { MediaRef } from "./media";
import type { TagRef } from "./tag";

export type PostStatus = "DRAFT" | "PUBLISHED" | "ARCHIVED";

export const POST_STATUSES: PostStatus[] = ["DRAFT", "PUBLISHED", "ARCHIVED"];

/** One row in the posts list — everything except the body. */
export interface PostSummary {
  id: string;
  title: string;
  slug: string;
  excerpt: string;
  status: PostStatus;
  featured: boolean;
  authors: AuthorRef[];
  tags: TagRef[];
  coverImage: MediaRef | null;
  wordCount: number;
  readingTimeMinutes: number;
  publishedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface Post extends PostSummary {
  content: ContentDoc;
  coverImageAlt: string;
  /** The .md file the content was imported from. */
  markdownSource: MarkdownSourceRef | null;
  seoTitle: string;
  metaDescription: string;
  focusKeyword: string;
  canonicalUrl: string;
  /** Optimistic-locking counter: a save carrying a stale version is rejected with 409. */
  version: number;
}

/** What the editor sends on create/update. Ids only for relations. */
export interface PostInput {
  title: string;
  slug: string;
  excerpt: string;
  content: ContentDoc;
  coverImageId: string | null;
  coverImageAlt: string;
  markdownSourceId: string | null;
  seoTitle: string;
  metaDescription: string;
  focusKeyword: string;
  canonicalUrl: string;
  featured: boolean;
  authorIds: string[];
  tagIds: string[];
}

export type PostSortKey = "updatedAt" | "title" | "publishedAt" | "wordCount";

export interface PostListParams {
  status?: PostStatus;
  q?: string;
  tagId?: string;
  authorId?: string;
  sort?: PostSortKey;
  direction?: "asc" | "desc";
  page?: number;
  size?: number;
}

/** Field limits shared by the editor's counters and validation (mirrored by the backend). */
export const POST_LIMITS = {
  title: 200,
  excerpt: 300,
  seoTitle: 70,
  metaDescription: 160,
  focusKeyword: 100,
} as const;
