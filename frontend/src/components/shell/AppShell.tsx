"use client";

import { useState, useEffect } from "react";
import { usePathname } from "next/navigation";
import { ToastProvider } from "@/components/ui/Toast";
import { NavigationGuardProvider } from "./NavigationGuard";
import { Sidebar } from "./Sidebar";
import { resolveBreadcrumb, TopBar } from "./TopBar";

/**
 * Persistent chrome around every signed-in screen: sidebar + top bar + scrollable
 * content. The one client boundary in the layout — pages stay Server Components.
 */
export function AppShell({ children }: { children: React.ReactNode }) {
  const [collapsed, setCollapsed] = useState(false);
  const pathname = usePathname();

 // Ensure sidebar starts closed on mobile, and auto-closes when navigating
  useEffect(() => {
    if (window.innerWidth < 768) {
      const timer = setTimeout(() => setCollapsed(true), 0);
      return () => clearTimeout(timer);
    }
  }, [pathname]);

  return (
    <ToastProvider>
      <NavigationGuardProvider>
        <div className="flex h-screen overflow-hidden relative w-full">
          
          {/* Mobile Backdrop (closes sidebar when clicked) */}
          {!collapsed && (
            <div 
              className="fixed inset-0 z-40 bg-black/20 md:hidden"
              onClick={() => setCollapsed(true)}
            />
          )}

          {/* Sidebar Mobile Drawer & Desktop Static */}
          <div 
            className={`absolute inset-y-0 left-0 z-50 transform transition-transform duration-200 md:relative md:translate-x-0 ${
              collapsed ? "-translate-x-full pointer-events-none md:pointer-events-auto" : "translate-x-0 pointer-events-auto"
            }`}
          >
            <Sidebar collapsed={collapsed} />
          </div>

          <main className="flex min-w-0 flex-1 flex-col overflow-hidden w-full">
            <TopBar breadcrumb={resolveBreadcrumb(pathname)} onToggleSidebar={() => setCollapsed((c) => !c)} />
            
            {/* Added overflow-auto to fix horizontal scrolling on mobile tables */}
            <div className="min-h-0 flex-1 overflow-auto">{children}</div>
          </main>
          
        </div>
      </NavigationGuardProvider>
    </ToastProvider>
  );
}