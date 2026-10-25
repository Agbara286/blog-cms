"use client";

import { usePathname, useRouter } from "next/navigation";
import { EllipsisVertical, FileText, Image as ImageIcon, LogOut, Tag, Users, type LucideIcon } from "lucide-react";
import { Menu } from "@/components/ui/Menu";
import { cn } from "@/lib/utils";
import { Logo } from "./Logo";
import { GuardedLink } from "./NavigationGuard";

interface NavItem {
  label: string;
  href: string;
  icon: LucideIcon;
}

const NAV: NavItem[] = [
  { label: "Posts", href: "/posts", icon: FileText },
  { label: "Authors", href: "/authors", icon: Users },
  { label: "Tags", href: "/tags", icon: Tag },
  { label: "Media", href: "/media", icon: ImageIcon },
];

export function Sidebar({ collapsed }: { collapsed: boolean }) {
  const pathname = usePathname();
  const router = useRouter();

  const signOut = async () => {
    await fetch("/api/auth/logout", { method: "POST" });
    router.replace("/login");
    router.refresh();
  };

  return (
   <aside
      className="flex h-full shrink-0 flex-col overflow-hidden border-r-[1.5px] border-line bg-page transition-[width] duration-200"
      style={{ width: collapsed ? "var(--shell-sidebar-w-collapsed)" : "var(--shell-sidebar-w)" }}
    >
      <div
        className={cn(
          "flex h-[var(--shell-topbar-h)] shrink-0 items-center border-b-[1.5px] border-line",
          collapsed ? "justify-center" : "px-4",
        )}
      >
        <GuardedLink href="/posts" aria-label="blog-cms — posts">
          <Logo collapsed={collapsed} />
        </GuardedLink>
      </div>

      <nav aria-label="Main" className={cn("flex flex-col gap-px py-2.5", collapsed ? "px-2" : "px-2.5")}>
        {NAV.map((item) => {
          const active = pathname === item.href || pathname.startsWith(`${item.href}/`);
          return (
            <GuardedLink
              key={item.href}
              href={item.href}
              title={item.label}
              aria-current={active ? "page" : undefined}
              className={cn(
                "flex items-center gap-2.5 rounded-md text-[12px] whitespace-nowrap transition-colors",
                collapsed ? "justify-center py-2" : "px-2.5 py-[6px]",
                active ? "bg-green font-bold text-ink" : "font-medium text-muted hover:bg-hover hover:text-ink",
              )}
            >
              <item.icon size={14} aria-hidden />
              {!collapsed && item.label}
            </GuardedLink>
          );
        })}
      </nav>

      <div className="flex-1" />

      <div className={cn("flex shrink-0 items-center gap-2.5 border-t border-line py-2.5", collapsed ? "justify-center px-0" : "px-3")}>
        <span className="flex h-[26px] w-[26px] shrink-0 items-center justify-center rounded-md bg-green font-mono text-[10px] font-bold">
          AD
        </span>
        {!collapsed && (
          <>
            <div className="min-w-0 flex-1">
              <div className="truncate text-[12px] font-bold">Admin</div>
              <div className="text-[10px] text-muted">Administrator</div>
            </div>
            <Menu
              align="left"
              trigger={(props) => (
                <button
                  type="button"
                  {...props}
                  aria-label="Account menu"
                  className="cursor-pointer rounded-sm p-1 text-muted hover:bg-hover hover:text-ink"
                >
                  <EllipsisVertical size={14} />
                </button>
              )}
              items={[{ label: "Sign out", icon: LogOut, onSelect: () => void signOut() }]}
            />
          </>
        )}
      </div>

    </aside>
  );
}
