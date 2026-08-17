"use client";

import type { ButtonHTMLAttributes, CSSProperties, ReactNode } from "react";
import { useIsMobile } from "@/hooks/useIsMobile";

type ConfigButtonVariant = "primary" | "secondary" | "danger" | "ghost";
type ConfigButtonSize = "small" | "default";

interface ConfigPanelShellProps {
  embedded: boolean;
  title: string;
  subtitle?: string;
  closeLabel?: string;
  onClose: () => void;
  children: ReactNode;
  width?: number;
  height?: string;
}

export function ConfigPanelShell({
  embedded,
  title,
  subtitle,
  closeLabel = "Close",
  onClose,
  children,
  width = 900,
  height = "78vh",
}: ConfigPanelShellProps) {
  const isMobile = useIsMobile();

  return (
    <div
      role={embedded ? undefined : "dialog"}
      aria-modal={embedded ? undefined : "true"}
      aria-label={title}
      onClick={(event) => {
        if (!embedded && event.target === event.currentTarget) onClose();
      }}
      style={embedded
        ? { width: "100%", height: "100%", minWidth: 0, minHeight: 0, display: "flex" }
        : { position: "fixed", inset: 0, zIndex: 1000, display: "flex", alignItems: "center", justifyContent: "center", background: "rgba(0,0,0,0.35)" }}
    >
      <div
        style={{
          width: embedded ? "100%" : isMobile ? "calc(100vw - 16px)" : width,
          maxWidth: embedded ? "none" : "calc(100vw - 16px)",
          height: embedded ? "100%" : isMobile ? "calc(100dvh - 16px)" : height,
          maxHeight: embedded ? "none" : "calc(100dvh - 16px)",
          minWidth: 0,
          minHeight: 0,
          display: "flex",
          flexDirection: "column",
          overflow: "hidden",
          border: embedded ? "none" : "1px solid var(--border)",
          borderRadius: embedded ? 0 : 8,
          background: "var(--bg)",
          boxShadow: embedded ? "none" : "0 8px 32px rgba(0,0,0,0.18)",
        }}
      >
        {!embedded && (
          <div style={{ height: 50, padding: "0 14px 0 18px", display: "flex", alignItems: "center", gap: 10, borderBottom: "1px solid var(--border)", flexShrink: 0 }}>
            <strong style={{ color: "var(--text)", fontSize: 15 }}>{title}</strong>
            {subtitle && (
              <code title={subtitle} style={{ minWidth: 0, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap", color: "var(--text-dim)", fontFamily: "var(--font-mono)", fontSize: 11 }}>
                {subtitle}
              </code>
            )}
            <button
              type="button"
              onClick={onClose}
              title={closeLabel}
              aria-label={closeLabel}
              style={{ marginLeft: "auto", width: 30, height: 30, display: "grid", placeItems: "center", border: "none", borderRadius: 5, background: "transparent", color: "var(--text-muted)", cursor: "pointer", fontSize: 20, lineHeight: 1 }}
            >
              ×
            </button>
          </div>
        )}
        {children}
      </div>
    </div>
  );
}

export function ConfigSplitView({ children }: { children: ReactNode }) {
  const isMobile = useIsMobile();
  return (
    <div style={{ minHeight: 0, flex: 1, display: "flex", flexDirection: isMobile ? "column" : "row", overflow: "hidden" }}>
      {children}
    </div>
  );
}

export function ConfigSidebar({ children }: { children: ReactNode }) {
  const isMobile = useIsMobile();
  return (
    <aside
      style={{
        width: isMobile ? "100%" : 240,
        height: isMobile ? 190 : "auto",
        minHeight: 0,
        borderRight: isMobile ? "none" : "1px solid var(--border)",
        borderBottom: isMobile ? "1px solid var(--border)" : "none",
        display: "flex",
        flexDirection: "column",
        flexShrink: 0,
        background: "var(--bg-panel)",
      }}
    >
      {children}
    </aside>
  );
}

export function ConfigSidebarList({ children }: { children: ReactNode }) {
  return <div style={{ minHeight: 0, flex: 1, overflowY: "auto", padding: "8px 6px" }}>{children}</div>;
}

export function ConfigDetail({ children, style }: { children: ReactNode; style?: CSSProperties }) {
  const isMobile = useIsMobile();
  return (
    <div style={{ minWidth: 0, minHeight: 0, flex: 1, overflowY: "auto", padding: isMobile ? 14 : 20, ...style }}>
      {children}
    </div>
  );
}

export function ConfigFooter({ status, children }: { status?: ReactNode; children?: ReactNode }) {
  return (
    <footer style={{ minHeight: 52, padding: "9px 14px", display: "flex", alignItems: "center", gap: 8, borderTop: "1px solid var(--border)", flexShrink: 0 }}>
      <div style={{ minWidth: 0, flex: 1, overflow: "hidden", color: "var(--text-dim)", fontSize: 11 }}>
        {status}
      </div>
      <div style={{ display: "flex", alignItems: "center", justifyContent: "flex-end", gap: 8, flexShrink: 0 }}>
        {children}
      </div>
    </footer>
  );
}

export function ConfigButton({
  variant = "secondary",
  size = "default",
  style,
  disabled,
  children,
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: ConfigButtonVariant; size?: ConfigButtonSize }) {
  const palette: Record<ConfigButtonVariant, CSSProperties> = {
    primary: { borderColor: "var(--accent)", background: "var(--accent)", color: "#fff", fontWeight: 600 },
    secondary: { borderColor: "var(--border)", background: "transparent", color: "var(--text-muted)" },
    danger: { borderColor: "rgba(239,68,68,0.35)", background: "rgba(239,68,68,0.06)", color: "#ef4444" },
    ghost: { borderColor: "transparent", background: "transparent", color: "var(--text-muted)" },
  };

  return (
    <button
      type="button"
      disabled={disabled}
      {...props}
      style={{
        minHeight: size === "small" ? 28 : 32,
        padding: size === "small" ? "0 10px" : "0 14px",
        display: "inline-flex",
        alignItems: "center",
        justifyContent: "center",
        gap: 6,
        borderWidth: 1,
        borderStyle: "solid",
        borderRadius: 5,
        cursor: disabled ? "default" : "pointer",
        fontSize: size === "small" ? 11 : 12,
        lineHeight: 1,
        whiteSpace: "nowrap",
        opacity: disabled ? 0.5 : 1,
        ...palette[variant],
        ...style,
      }}
    >
      {children}
    </button>
  );
}

export function ConfigSwitch({ checked, disabled = false, loading = false, label, onChange }: { checked: boolean; disabled?: boolean; loading?: boolean; label: string; onChange: (checked: boolean) => void }) {
  const inactive = disabled || loading;
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      title={label}
      disabled={inactive}
      onClick={() => onChange(!checked)}
      style={{ position: "relative", width: 40, height: 22, padding: 0, border: "none", borderRadius: 11, background: checked ? "var(--accent)" : "var(--border)", cursor: loading ? "wait" : disabled ? "default" : "pointer", opacity: inactive ? 0.55 : 1, flexShrink: 0, transition: "background 0.18s" }}
    >
      <span style={{ position: "absolute", top: 3, left: checked ? 21 : 3, width: 16, height: 16, borderRadius: "50%", background: "var(--bg)", boxShadow: "0 1px 4px rgba(0,0,0,0.22)", transition: "left 0.18s cubic-bezier(.4,0,.2,1)" }} />
    </button>
  );
}

export function ConfigListAction({ active = false, children, ...props }: ButtonHTMLAttributes<HTMLButtonElement> & { active?: boolean }) {
  return (
    <div style={{ padding: "8px 6px", borderTop: "1px solid var(--border)", flexShrink: 0 }}>
      <button
        type="button"
        {...props}
        style={{ width: "100%", minHeight: 32, padding: "0 8px", display: "flex", alignItems: "center", justifyContent: "flex-start", gap: 6, border: "none", borderRadius: 5, background: active ? "var(--bg-selected)" : "transparent", color: active ? "var(--accent)" : "var(--text-dim)", cursor: props.disabled ? "default" : "pointer", fontSize: 12, opacity: props.disabled ? 0.5 : 1, ...props.style }}
      >
        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
          <path d="M12 5v14M5 12h14" />
        </svg>
        {children}
      </button>
    </div>
  );
}

export function ConfigStatusDot({ active, color }: { active?: boolean; color?: string }) {
  return (
    <span
      aria-hidden="true"
      style={{ width: 7, height: 7, borderRadius: "50%", background: color ?? (active ? "var(--accent)" : "var(--text-dim)"), flexShrink: 0, opacity: active === false ? 0.7 : 1 }}
    />
  );
}
