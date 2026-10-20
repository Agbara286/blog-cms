import StarterKit from "@tiptap/starter-kit";
import Image from "@tiptap/extension-image";
import { Placeholder } from "@tiptap/extensions";
import { Markdown } from "@tiptap/markdown";

/**
 * Image node that also remembers which media-library item it came from
 * (`attrs.mediaId`), so the backend can tell which images a post uses — e.g. to
 * stop one being deleted from the library while it's still in a post.
 */
const BlogImage = Image.extend({
  addAttributes() {
    return {
      ...this.parent?.(),
      mediaId: {
        default: null,
        parseHTML: (element) => element.getAttribute("data-media-id"),
        renderHTML: (attributes) => (attributes.mediaId ? { "data-media-id": attributes.mediaId } : {}),
      },
    };
  },
}).configure({ allowBase64: true });

/** The post editor's extensions; shared with the tests so they parse Markdown exactly like the editor. */
export const editorExtensions = [
  StarterKit.configure({
    heading: { levels: [2, 3] },
    link: { openOnClick: false, autolink: true, defaultProtocol: "https" },
  }),
  BlogImage,
  Placeholder.configure({ placeholder: "Start writing your post…" }),
  // For parsing imported .md files only; posts are still saved as JSON.
  Markdown,
];
