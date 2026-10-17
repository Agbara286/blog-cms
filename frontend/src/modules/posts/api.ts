"use client";

import { api, apiJson, apiRequest, query } from "@/lib/api-client";
import type { Page } from "@/types/api";
import type { MarkdownSourceRef } from "@/types/markdown";
import type { Post, PostInput, PostListParams, PostStatus, PostSummary } from "@/types/post";

/* Posts API — thin wrappers over the backend's /api/v1/posts endpoints. */

const DEFAULT_PAGE_SIZE = 10;

/** GET /api/v1/posts?status&q&tag&author&sort&direction&page&size */
export async function listPosts(params: PostListParams = {}): Promise<Page<PostSummary>> {
  const { status, q, tagId, authorId, sort, direction, page = 0, size = DEFAULT_PAGE_SIZE } = params;
  const result = await apiRequest<PostSummary[]>(
    `/posts${query({ status, q: q?.trim(), tag: tagId, author: authorId, sort, direction, page, size })}`,
  );
  return {
    items: result.data,
    meta: result.meta ?? { page, size, totalElements: result.data.length, totalPages: 1 },
  };
}

/** GET /api/v1/posts/counts — number of posts per status, for the list tabs. */
export function countPostsByStatus(): Promise<Record<PostStatus | "ALL", number>> {
  return api("GET", "/posts/counts");
}

/** GET /api/v1/posts/{id} */
export function getPost(id: string): Promise<Post> {
  return api("GET", `/posts/${id}`);
}

/** POST /api/v1/posts — new posts always start as drafts. */
export function createPost(input: PostInput): Promise<Post> {
  return apiJson("POST", "/posts", input);
}

/**
 * PUT /api/v1/posts/{id}
 * `version` is the one the editor loaded; a mismatch means someone else saved in
 * between, and the backend answers 409 rather than overwriting their work.
 */
export function updatePost(id: string, input: PostInput, version: number): Promise<Post> {
  return apiJson("PUT", `/posts/${id}`, { ...input, version });
}

/** POST /api/v1/posts/{id}/publish — requires a title and some content. */
export function publishPost(id: string): Promise<Post> {
  return api("POST", `/posts/${id}/publish`);
}

/** POST /api/v1/posts/{id}/unpublish — back to draft (also restores archived posts). */
export function unpublishPost(id: string): Promise<Post> {
  return api("POST", `/posts/${id}/unpublish`);
}

/** POST /api/v1/posts/{id}/archive */
export function archivePost(id: string): Promise<Post> {
  return api("POST", `/posts/${id}/archive`);
}

/** POST /api/v1/posts/{id}/duplicate — copies content and metadata into a new draft. */
export function duplicatePost(id: string): Promise<Post> {
  return api("POST", `/posts/${id}/duplicate`);
}

/** DELETE /api/v1/posts/{id} */
export async function deletePost(id: string): Promise<void> {
  await api("DELETE", `/posts/${id}`);
}

/** POST /api/v1/markdown-sources (multipart, field `file`) — stores the original .md file of an import. */
export async function uploadMarkdownSource(file: File): Promise<MarkdownSourceRef> {
  const form = new FormData();
  form.append("file", file);
  return (await apiRequest<MarkdownSourceRef>("/markdown-sources", { method: "POST", body: form })).data;
}
