"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { Archive, ArrowLeft, Copy, EllipsisVertical, FileQuestion, Send, Trash2, Undo2 } from "lucide-react";
import { GuardedLink, useBlockNavigation, useGuardedNavigate } from "@/components/shell/NavigationGuard";
import { ConfirmDialog } from "@/components/ui/ConfirmDialog";
import { EmptyState } from "@/components/ui/EmptyState";
import { ErrorState } from "@/components/ui/ErrorState";
import { Menu, type MenuItem } from "@/components/ui/Menu";
import { Spinner } from "@/components/ui/Spinner";
import { PostStatusChip } from "@/components/ui/StatusChip";
import { useToast } from "@/components/ui/Toast";
import { useAsync } from "@/hooks/useAsync";
import { useUnsavedChangesWarning } from "@/hooks/useUnsavedChangesWarning";
import { countWords, readingTimeMinutes } from "@/lib/content";
import { timeAgo } from "@/lib/format";
import { slugify } from "@/lib/slug";
import { ApiError } from "@/types/api";
import { POST_LIMITS, type Post } from "@/types/post";
import { archivePost, createPost, deletePost, duplicatePost, getPost, publishPost, unpublishPost, updatePost } from "../api";
import { EditorSidebar, type EditorMeta } from "./EditorSidebar";
import { EMPTY_FORM, formFromPost, inputFromForm, validateForm, type EditorForm } from "./form";
import { RichTextEditor } from "./RichTextEditor";

/** Route entry: loads the post (or starts a blank one), then hands over to the editor. */
export function PostEditorCanvas({ postId }: { postId?: string }) {
  const post = useAsync(() => (postId ? getPost(postId) : Promise.resolve(null)), [postId]);

  if (post.error) {
    const notFound = post.error instanceof ApiError && post.error.status === 404;
    return (
      <div className="mx-auto max-w-[720px] p-[var(--page-pad)]">
        {notFound ? (
          <EmptyState
            icon={FileQuestion}
            title="Post not found"
            description="It may have been deleted."
            action={
              <Link href="/posts" className="btn btn-primary btn-sm">
                Back to posts
              </Link>
            }
          />
        ) : (
          <ErrorState title="Couldn't load this post" error={post.error} onRetry={post.reload} />
        )}
      </div>
    );
  }
  if (post.loading || post.data === undefined) return <EditorSkeleton />;
  // Keyed so opening another post starts from a clean editor state.
  return <PostEditor key={post.data?.id ?? "new"} initial={post.data} />;
}

type Busy = "save" | "publish" | "status" | null;

function PostEditor({ initial }: { initial: Post | null }) {
  const router = useRouter();
  const toast = useToast();

  const initialForm = initial ? formFromPost(initial) : EMPTY_FORM;
  const [form, setForm] = useState<EditorForm>(initialForm);
  const [savedSnapshot, setSavedSnapshot] = useState(() => JSON.stringify(initialForm));
  const [post, setPost] = useState<Post | null>(initial);
  const [busy, setBusy] = useState<Busy>(null);
  const [showErrors, setShowErrors] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [importing, setImporting] = useState(false);

  // The slug follows the title until someone edits it by hand — except on posts that
  // already have their own slug, where changing the title must not move the URL.
  const slugFollowsTitle = useRef(!initial || (initial.status === "DRAFT" && initial.slug === slugify(initial.title)));

  const dirty = JSON.stringify(form) !== savedSnapshot;
  // A Markdown import still uploading counts as unsaved: leaving now would lose it.
  useUnsavedChangesWarning(dirty || importing);
  useBlockNavigation(dirty || importing);
  const guardedNavigate = useGuardedNavigate();

  const errors = useMemo(() => validateForm(form), [form]);
  const hasErrors = Object.keys(errors).length > 0;
  const wordCount = useMemo(() => countWords(form.content), [form.content]);

  const meta: EditorMeta = {
    status: post?.status ?? "DRAFT",
    publishedAt: post?.publishedAt ?? null,
    updatedAt: post?.updatedAt ?? null,
  };

  const change = useCallback((patch: Partial<EditorForm>) => {
    setForm((current) => {
      const next = { ...current, ...patch };
      if (patch.title !== undefined && slugFollowsTitle.current) next.slug = slugify(patch.title);
      return next;
    });
  }, []);

  const onContentChange = useCallback((content: EditorForm["content"]) => change({ content }), [change]);
  const onMarkdownSourceChange = useCallback(
    (markdownSource: EditorForm["markdownSource"]) => change({ markdownSource }),
    [change],
  );

  const reportError = (error: unknown) => {
    toast.error(error instanceof Error ? error.message : "Something went wrong.");
  };

  /** Saves the form; resolves to the saved post, or null if it couldn't be saved. */
  const save = async (options: { quiet?: boolean } = {}): Promise<Post | null> => {
    if (hasErrors) {
      setShowErrors(true);
      toast.error("Fix the highlighted fields before saving.");
      return null;
    }
    const input = inputFromForm(form);
    setBusy("save");
    try {
      const saved = post ? await updatePost(post.id, input, post.version) : await createPost(input);
      setPost(saved);
      // The server may have filled in a slug; keep the form in step so it isn't dirty.
      const nextForm = { ...form, slug: saved.slug };
      setForm(nextForm);
      setSavedSnapshot(JSON.stringify(nextForm));
      setShowErrors(false);
      if (!options.quiet) toast.success(post ? "Changes saved." : "Draft created.");
      if (!post) router.replace(`/posts/${saved.id}`);
      return saved;
    } catch (error) {
      reportError(error);
      return null;
    } finally {
      setBusy(null);
    }
  };

  // Latest save() for the keyboard shortcut, without re-binding the listener every render.
  const saveRef = useRef(save);
  useEffect(() => {
    saveRef.current = save;
  });
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "s") {
        event.preventDefault();
        void saveRef.current();
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, []);

  /** Status changes act on the saved post, so unsaved edits are saved first. */
  const changeStatus = async (action: (id: string) => Promise<Post>, success: string, kind: Busy) => {
    const current = dirty || !post ? await save({ quiet: true }) : post;
    if (!current) return;
    setBusy(kind);
    try {
      const updated = await action(current.id);
      setPost(updated);
      toast.success(success);
    } catch (error) {
      reportError(error);
    } finally {
      setBusy(null);
    }
  };

  const status = meta.status;
  const menuItems: (MenuItem | "separator")[] = [
    ...(post
      ? [
          {
            label: "Duplicate",
            icon: Copy,
            onSelect: async () => {
              try {
                const copy = await duplicatePost(post.id);
                toast.success("Copy created from the last saved version.");
                guardedNavigate(`/posts/${copy.id}`);
              } catch (error) {
                reportError(error);
              }
            },
          } satisfies MenuItem,
        ]
      : []),
    ...(status === "PUBLISHED"
      ? [{ label: "Unpublish", icon: Undo2, onSelect: () => void changeStatus(unpublishPost, "Moved back to drafts.", "status") }]
      : []),
    ...(status !== "ARCHIVED" && post
      ? [{ label: "Archive", icon: Archive, onSelect: () => void changeStatus(archivePost, "Post archived.", "status") }]
      : []),
    ...(post ? ["separator" as const, { label: "Delete", icon: Trash2, danger: true, onSelect: () => setConfirmDelete(true) }] : []),
  ];

  const saveState = busy === "save" ? "Saving…" : dirty ? "Unsaved changes" : post ? `Saved ${timeAgo(post.updatedAt)}` : "Not saved yet";

  return (
    <div className="flex flex-col">
      {/* Action bar */}
      <div className="sticky top-0 z-20 flex items-center gap-3 border-b border-line bg-page/95 px-[var(--page-pad)] py-2.5 backdrop-blur">
        <GuardedLink href="/posts" className="flex items-center gap-1.5 text-[12px] font-semibold text-secondary hover:text-ink">
          <ArrowLeft size={14} aria-hidden /> Posts
        </GuardedLink>
        <span aria-hidden className="h-4 w-px bg-line" />
        <PostStatusChip status={status} />
        <span className={dirty ? "text-[12px] font-semibold text-warning" : "text-[12px] text-muted"} aria-live="polite">
          {saveState}
        </span>

        <div className="ml-auto flex items-center gap-2">
          {status === "PUBLISHED" ? (
            <button type="button" className="btn btn-primary" onClick={() => void save()} disabled={busy !== null || (!dirty && Boolean(post))}>
              {busy === "save" && <Spinner size={12} />}
              Update
            </button>
          ) : (
            <>
              <button type="button" className="btn btn-secondary" onClick={() => void save()} disabled={busy !== null || (!dirty && Boolean(post))}>
                {busy === "save" && <Spinner size={12} />}
                {status === "ARCHIVED" ? "Save" : "Save draft"}
              </button>
              {status === "ARCHIVED" ? (
                <button
                  type="button"
                  className="btn btn-primary"
                  disabled={busy !== null}
                  onClick={() => void changeStatus(unpublishPost, "Restored to drafts.", "status")}
                >
                  {busy === "status" ? <Spinner size={12} /> : <Undo2 size={13} aria-hidden />}
                  Restore
                </button>
              ) : (
                <button
                  type="button"
                  className="btn btn-green"
                  disabled={busy !== null}
                  onClick={() => void changeStatus(publishPost, "Post published.", "publish")}
                >
                  {busy === "publish" ? <Spinner size={12} /> : <Send size={13} aria-hidden />}
                  Publish
                </button>
              )}
            </>
          )}
          {menuItems.length > 0 && (
            <Menu
              items={menuItems}
              trigger={(props) => (
                <button
                  type="button"
                  {...props}
                  aria-label="More actions"
                  className="flex h-8 w-8 cursor-pointer items-center justify-center rounded-full text-muted hover:bg-hover hover:text-ink"
                >
                  <EllipsisVertical size={16} />
                </button>
              )}
            />
          )}
        </div>
      </div>

      {/* Writing area + sidebar */}
      <div className="mx-auto grid w-full max-w-[1280px] grid-cols-1 gap-5 p-[var(--page-pad)] lg:grid-cols-[minmax(0,1fr)_var(--editor-sidebar-w)]">
        <div className="flex min-w-0 flex-col gap-3">
          <div>
            <label htmlFor="post-title" className="sr-only">
              Title
            </label>
            <textarea
              id="post-title"
              rows={1}
              value={form.title}
              onChange={(e) => change({ title: e.target.value.replace(/\n/g, " ") })}
              placeholder="Post title"
              aria-invalid={showErrors && Boolean(errors.title)}
              className="w-full resize-none bg-transparent text-[30px] leading-tight font-bold tracking-[-0.02em] outline-none [field-sizing:content] placeholder:text-[var(--atomity-gray-300)]"
            />
            <div className="flex justify-between text-[11px]">
              <span className="text-error-text">{showErrors ? errors.title : ""}</span>
              {form.title.length > POST_LIMITS.title * 0.8 && (
                <span className={form.title.length > POST_LIMITS.title ? "font-bold text-error" : "text-muted"}>
                  {form.title.length}/{POST_LIMITS.title}
                </span>
              )}
            </div>
          </div>
          <RichTextEditor
            value={form.content}
            onChange={onContentChange}
            markdownSource={form.markdownSource}
            onMarkdownSourceChange={onMarkdownSourceChange}
            onImportingChange={setImporting}
          />
        </div>

        <aside aria-label="Post settings" className="min-w-0">
          <EditorSidebar
            form={form}
            errors={showErrors ? errors : {}}
            meta={meta}
            wordCount={wordCount}
            readingTime={readingTimeMinutes(wordCount)}
            onChange={change}
            onSlugEdited={() => {
              slugFollowsTitle.current = false;
            }}
          />
        </aside>
      </div>

      {confirmDelete && post && (
        <ConfirmDialog
          title="Delete this post?"
          message="It will be permanently deleted. This can’t be undone — archive it instead if you might need it again."
          confirmLabel="Delete"
          danger
          onClose={() => setConfirmDelete(false)}
          onConfirm={async () => {
            try {
              await deletePost(post.id);
            } catch (error) {
              reportError(error);
              throw error;
            }
            setSavedSnapshot(JSON.stringify(form));
            toast.success("Post deleted.");
            router.push("/posts");
          }}
        />
      )}
    </div>
  );
}

function EditorSkeleton() {
  return (
    <div className="flex flex-col" aria-busy="true" aria-label="Loading post">
      <div className="h-[53px] border-b border-line" />
      <div className="mx-auto grid w-full max-w-[1280px] grid-cols-1 gap-5 p-[var(--page-pad)] lg:grid-cols-[minmax(0,1fr)_var(--editor-sidebar-w)]">
        <div className="flex flex-col gap-4">
          <div className="skeleton h-9 w-2/3" />
          <div className="skeleton h-[460px] w-full !rounded-lg" />
        </div>
        <div className="flex flex-col gap-3">
          {[120, 180, 160, 140].map((h) => (
            <div key={h} className="skeleton w-full !rounded-lg" style={{ height: h }} />
          ))}
        </div>
      </div>
    </div>
  );
}
