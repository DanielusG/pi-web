"use client";

import { useEffect, useState, type ReactNode } from "react";
import { useI18n } from "@/hooks/useI18n";
import { useIsMobile } from "@/hooks/useIsMobile";
import { useTheme, type ThemePreference } from "@/hooks/useTheme";
import {
  getLastSettingsSection,
  setLastSettingsSection,
  type SettingsSection,
} from "@/lib/settings-navigation";
import { ModelsConfig } from "./ModelsConfig";
import { SkillsConfig } from "./SkillsConfig";
import { AgentsConfig } from "./AgentsConfig";
import { PluginsConfig } from "./PluginsConfig";

interface Props {
  cwd: string | null;
  sessionId: string | null;
  onClose: () => void;
  onPluginsReloaded: () => void;
}

function SectionIcon({ section }: { section: SettingsSection }) {
  const common = {
    width: 16,
    height: 16,
    viewBox: "0 0 24 24",
    fill: "none",
    stroke: "currentColor",
    strokeWidth: 1.8,
    strokeLinecap: "round" as const,
    strokeLinejoin: "round" as const,
    "aria-hidden": true,
  };

  if (section === "general") return <svg {...common}><circle cx="12" cy="12" r="3" /><path d="M19.4 15a1.7 1.7 0 0 0 .34 1.88l.06.06-2.83 2.83-.06-.06a1.7 1.7 0 0 0-1.88-.34 1.7 1.7 0 0 0-1.03 1.56V21h-4v-.08A1.7 1.7 0 0 0 9 19.37a1.7 1.7 0 0 0-1.88.34l-.06.06-2.83-2.83.06-.06A1.7 1.7 0 0 0 4.63 15 1.7 1.7 0 0 0 3.08 14H3v-4h.08A1.7 1.7 0 0 0 4.63 9a1.7 1.7 0 0 0-.34-1.88l-.06-.06 2.83-2.83.06.06A1.7 1.7 0 0 0 9 4.63 1.7 1.7 0 0 0 10 3.08V3h4v.08A1.7 1.7 0 0 0 15 4.63a1.7 1.7 0 0 0 1.88-.34l.06-.06 2.83 2.83-.06.06A1.7 1.7 0 0 0 19.37 9 1.7 1.7 0 0 0 20.92 10H21v4h-.08A1.7 1.7 0 0 0 19.4 15Z" /></svg>;
  if (section === "models") return <svg {...common}><rect x="4" y="4" width="16" height="16" rx="2" /><rect x="9" y="9" width="6" height="6" /><path d="M9 1v3M15 1v3M9 20v3M15 20v3M20 9h3M20 15h3M1 9h3M1 15h3" /></svg>;
  if (section === "skills") return <svg {...common}><path d="m12 2-10 5 10 5 10-5-10-5Z" /><path d="m2 12 10 5 10-5M2 17l10 5 10-5" /></svg>;
  if (section === "agents") return <svg {...common}><circle cx="12" cy="8" r="4" /><path d="M4 21a8 8 0 0 1 16 0M19 5v4M17 7h4" /></svg>;
  return <svg {...common}><path d="M9 7V2M15 7V2M6 13V8a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v5a6 6 0 0 1-12 0ZM12 19v3" /></svg>;
}

function ThemeIcon({ preference }: { preference: ThemePreference }) {
  if (preference === "light") {
    return <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" aria-hidden="true"><circle cx="12" cy="12" r="4" /><path d="M12 2v2M12 20v2M4.93 4.93l1.42 1.42M17.66 17.66l1.41 1.41M2 12h2M20 12h2M4.93 19.07l1.42-1.41M17.66 6.34l1.41-1.41" /></svg>;
  }
  if (preference === "dark") {
    return <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79Z" /></svg>;
  }
  return <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><rect x="3" y="4" width="18" height="13" rx="2" /><path d="M8 21h8M12 17v4" /></svg>;
}

function GeneralSettings() {
  const { locale, setLocale, supportedLocales, t } = useI18n();
  const { preference, setThemePreference } = useTheme();
  const themeOptions: { id: ThemePreference; label: string }[] = [
    { id: "light", label: t("settings.themeLight") },
    { id: "dark", label: t("settings.themeDark") },
    { id: "auto", label: t("settings.themeSystem") },
  ];

  return (
    <div style={{ width: "100%", maxWidth: 680, padding: "26px clamp(18px, 4vw, 40px) 40px", overflowY: "auto" }}>
      <h2 style={{ margin: 0, color: "var(--text)", fontSize: 18, fontWeight: 700 }}>{t("settings.general")}</h2>

      <section style={{ marginTop: 24 }}>
        <h3 style={{ margin: "0 0 5px", color: "var(--text)", fontSize: 13, fontWeight: 650 }}>{t("settings.appearance")}</h3>
        <p style={{ margin: "0 0 12px", color: "var(--text-dim)", fontSize: 11, lineHeight: 1.5 }}>{t("settings.appearanceDescription")}</p>
        <div role="radiogroup" aria-label={t("settings.appearance")} style={{ display: "grid", gridTemplateColumns: "repeat(3, minmax(0, 1fr))", width: "100%", maxWidth: 420, padding: 3, gap: 3, borderRadius: 7, background: "var(--bg-panel)" }}>
          {themeOptions.map((option) => {
            const selected = preference === option.id;
            return (
              <button
                key={option.id}
                type="button"
                role="radio"
                aria-checked={selected}
                onClick={() => setThemePreference(option.id)}
                style={{
                  minWidth: 0,
                  height: 42,
                  padding: "0 8px",
                  display: "flex",
                  alignItems: "center",
                  justifyContent: "center",
                  gap: 7,
                  border: "none",
                  borderRadius: 5,
                  background: selected ? "var(--bg-selected)" : "transparent",
                  color: selected ? "var(--accent)" : "var(--text-muted)",
                  cursor: "pointer",
                  fontSize: 12,
                  fontWeight: selected ? 600 : 400,
                }}
              >
                <ThemeIcon preference={option.id} />
                <span style={{ minWidth: 0, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{option.label}</span>
              </button>
            );
          })}
        </div>
      </section>

      <section style={{ marginTop: 30 }}>
        <h3 style={{ margin: "0 0 5px", color: "var(--text)", fontSize: 13, fontWeight: 650 }}>{t("common.language")}</h3>
        <p style={{ margin: "0 0 12px", color: "var(--text-dim)", fontSize: 11, lineHeight: 1.5 }}>{t("settings.languageDescription")}</p>
        <div role="radiogroup" aria-label={t("common.language")} style={{ display: "flex", flexDirection: "column", width: "100%", maxWidth: 420, gap: 3 }}>
          {supportedLocales.map((plugin) => {
            const selected = locale === plugin.id;
            return (
              <button
                key={plugin.id}
                type="button"
                role="radio"
                aria-checked={selected}
                onClick={() => setLocale(plugin.id as typeof locale)}
                style={{
                  height: 44,
                  padding: "0 10px",
                  display: "flex",
                  alignItems: "center",
                  gap: 10,
                  border: "none",
                  borderRadius: 5,
                  background: selected ? "var(--bg-selected)" : "transparent",
                  color: "var(--text)",
                  cursor: "pointer",
                  fontSize: 12,
                  textAlign: "left",
                }}
              >
                <span style={{ width: 16, height: 16, display: "grid", placeItems: "center", border: `1px solid ${selected ? "var(--accent)" : "var(--border)"}`, borderRadius: "50%", flexShrink: 0 }}>
                  {selected && <span style={{ width: 8, height: 8, borderRadius: "50%", background: "var(--accent)" }} />}
                </span>
                <span style={{ flex: 1 }}>{plugin.label}</span>
                <span style={{ color: "var(--text-dim)", fontFamily: "var(--font-mono)", fontSize: 10 }}>{plugin.id}</span>
              </button>
            );
          })}
        </div>
      </section>
    </div>
  );
}

export function SettingsPanel({ cwd, sessionId, onClose, onPluginsReloaded }: Props) {
  const isMobile = useIsMobile();
  const { t } = useI18n();
  const [section, setSection] = useState<SettingsSection>(() => getLastSettingsSection(cwd));
  const sections: { id: SettingsSection; label: string; requiresProject: boolean }[] = [
    { id: "general", label: t("settings.general"), requiresProject: false },
    { id: "models", label: t("common.models"), requiresProject: false },
    { id: "skills", label: t("common.skills"), requiresProject: true },
    { id: "agents", label: t("common.agents"), requiresProject: true },
    { id: "plugins", label: t("common.plugins"), requiresProject: true },
  ];

  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      event.preventDefault();
      onClose();
    };
    document.addEventListener("keydown", handleKeyDown);
    return () => document.removeEventListener("keydown", handleKeyDown);
  }, [onClose]);

  useEffect(() => {
    if (cwd || (section !== "skills" && section !== "agents" && section !== "plugins")) return;
    setSection("general");
    setLastSettingsSection("general");
  }, [cwd, section]);

  let content: ReactNode;
  if (section === "general") content = <GeneralSettings />;
  else if (section === "models") content = <ModelsConfig embedded onClose={onClose} />;
  else if (!cwd) content = null;
  else if (section === "skills") content = <SkillsConfig embedded key={cwd} cwd={cwd} onClose={onClose} />;
  else if (section === "agents") content = <AgentsConfig embedded key={cwd} cwd={cwd} onClose={onClose} />;
  else content = <PluginsConfig embedded key={cwd} cwd={cwd} sessionId={sessionId} onClose={onClose} onReloaded={onPluginsReloaded} />;

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label={t("settings.title")}
      onClick={(event) => { if (event.target === event.currentTarget) onClose(); }}
      style={{ position: "fixed", inset: 0, zIndex: 1000, display: "flex", alignItems: "center", justifyContent: "center", background: "rgba(0,0,0,0.38)" }}
    >
      <div style={{ width: isMobile ? "calc(100vw - 12px)" : 1080, maxWidth: "calc(100vw - 16px)", height: isMobile ? "calc(100dvh - 12px)" : "84vh", maxHeight: "calc(100dvh - 16px)", display: "flex", flexDirection: "column", overflow: "hidden", border: "1px solid var(--border)", borderRadius: 8, background: "var(--bg)", boxShadow: "0 16px 48px rgba(0,0,0,0.22)" }}>
        <div style={{ position: "relative", minHeight: 50, padding: "0 52px 0 18px", display: "flex", alignItems: "center", borderBottom: "1px solid var(--border)", flexShrink: 0 }}>
          <strong style={{ color: "var(--text)", fontSize: 15 }}>{t("settings.title")}</strong>
          <button type="button" onClick={onClose} title={t("i18n.close")} aria-label={t("i18n.close")} style={{ position: "absolute", right: 14, top: 10, width: 30, height: 30, display: "grid", placeItems: "center", border: "none", borderRadius: 5, background: "transparent", color: "var(--text-muted)", cursor: "pointer", fontSize: 20, lineHeight: 1 }}>×</button>
        </div>

        <div style={{ minHeight: 0, flex: 1, display: "flex", flexDirection: isMobile ? "column" : "row" }}>
          <nav aria-label={t("settings.title")} style={{ width: isMobile ? "100%" : 188, minHeight: isMobile ? 50 : 0, padding: isMobile ? "6px" : "10px 8px", display: "flex", flexDirection: isMobile ? "row" : "column", gap: 2, overflowX: isMobile ? "auto" : "hidden", overflowY: isMobile ? "hidden" : "auto", borderRight: isMobile ? "none" : "1px solid var(--border)", borderBottom: isMobile ? "1px solid var(--border)" : "none", background: "var(--bg-panel)", flexShrink: 0 }}>
            {sections.map((item) => {
              const selected = section === item.id;
              const disabled = item.requiresProject && !cwd;
              return (
                <button
                  key={item.id}
                  type="button"
                  disabled={disabled}
                  title={disabled ? t("settings.projectRequired") : item.label}
                  aria-current={selected ? "page" : undefined}
                  onClick={() => {
                    setSection(item.id);
                    setLastSettingsSection(item.id);
                  }}
                  style={{ minWidth: isMobile ? 92 : 0, width: isMobile ? "auto" : "100%", height: 36, padding: "0 10px", display: "flex", alignItems: "center", gap: 9, border: "none", borderRadius: 5, background: selected ? "var(--bg-selected)" : "transparent", color: selected ? "var(--text)" : "var(--text-muted)", cursor: disabled ? "default" : "pointer", opacity: disabled ? 0.38 : 1, fontSize: 12, fontWeight: selected ? 600 : 400, textAlign: "left", flexShrink: 0 }}
                >
                  <SectionIcon section={item.id} />
                  <span>{item.label}</span>
                </button>
              );
            })}
          </nav>

          <main style={{ minWidth: 0, minHeight: 0, flex: 1, display: "flex", overflow: "hidden" }}>
            {content}
          </main>
        </div>
      </div>
    </div>
  );
}
