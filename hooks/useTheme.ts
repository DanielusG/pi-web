"use client";

import { useCallback, useSyncExternalStore } from "react";

export type ThemeMode = "light" | "dark" | "system";
export type EffectiveTheme = "light" | "dark";

interface ThemeState {
  mode: ThemeMode;
  effective: EffectiveTheme;
}

const MODE_KEY = "pi-theme-mode";
const LEGACY_KEY = "pi-theme";
const SYSTEM_QUERY = "(prefers-color-scheme: dark)";

const SERVER_SNAPSHOT: ThemeState = { mode: "system", effective: "light" };

const listeners = new Set<() => void>();

let cachedState: ThemeState | null = null;
let systemPreference: EffectiveTheme = "light";
let systemBound = false;

function isValidMode(value: unknown): value is ThemeMode {
  return value === "light" || value === "dark" || value === "system";
}

/** Pure read — never writes to localStorage. Falls back to legacy key. */
function readStoredMode(): ThemeMode {
  if (typeof window === "undefined") return "system";
  try {
    const raw = localStorage.getItem(MODE_KEY);
    if (isValidMode(raw)) return raw;
    const legacy = localStorage.getItem(LEGACY_KEY);
    if (isValidMode(legacy) && legacy !== "system") return legacy;
  } catch {
    // ignore storage errors (private mode, quota, etc.)
  }
  return "system";
}

function persistMode(mode: ThemeMode) {
  if (typeof window === "undefined") return;
  try {
    localStorage.setItem(MODE_KEY, mode);
    localStorage.removeItem(LEGACY_KEY);
  } catch {
    // ignore storage errors (private mode, quota, etc.)
  }
}

function readSystemPreference(): EffectiveTheme {
  if (typeof window === "undefined" || !window.matchMedia) return "light";
  return window.matchMedia(SYSTEM_QUERY).matches ? "dark" : "light";
}

function computeEffective(mode: ThemeMode, system: EffectiveTheme): EffectiveTheme {
  return mode === "system" ? system : mode;
}

function applyDom(effective: EffectiveTheme) {
  if (typeof document === "undefined") return;
  const root = document.documentElement;
  if (effective === "dark") {
    root.classList.add("dark");
  } else {
    root.classList.remove("dark");
  }
}

function withTransition(fn: () => void) {
  if (typeof window === "undefined") {
    fn();
    return;
  }
  const root = document.documentElement;
  const reduceMotion = window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;
  const animate = !reduceMotion;
  if (animate) root.classList.add("theme-transition");
  fn();
  if (animate) window.setTimeout(() => root.classList.remove("theme-transition"), 220);
}

function emit() {
  listeners.forEach((cb) => cb());
}

function ensureSystemBinding() {
  if (systemBound || typeof window === "undefined" || !window.matchMedia) return;
  const mql = window.matchMedia(SYSTEM_QUERY);
  const handler = (e: MediaQueryListEvent) => {
    systemPreference = e.matches ? "dark" : "light";
    if (!cachedState || cachedState.mode !== "system") return;
    const nextEffective = systemPreference;
    if (nextEffective === cachedState.effective) return;
    cachedState = { mode: "system", effective: nextEffective };
    withTransition(() => applyDom(nextEffective));
    emit();
  };
  if (typeof mql.addEventListener === "function") {
    mql.addEventListener("change", handler);
  } else if (typeof mql.addListener === "function") {
    mql.addListener(handler);
  }
  systemBound = true;
}

/** Initialize module state once on the client. Called at module load so
 *  getSnapshot stays free of DOM/localStorage side effects. */
function init() {
  if (typeof window === "undefined") return;
  ensureSystemBinding();
  systemPreference = readSystemPreference();
  const mode = readStoredMode();
  const effective = computeEffective(mode, systemPreference);
  cachedState = { mode, effective };
  applyDom(effective);
}

init();

function getSnapshot(): ThemeState {
  if (typeof window === "undefined") return SERVER_SNAPSHOT;
  return cachedState ?? SERVER_SNAPSHOT;
}

function getServerSnapshot(): ThemeState {
  return SERVER_SNAPSHOT;
}

function subscribe(cb: () => void): () => void {
  listeners.add(cb);
  ensureSystemBinding();
  return () => {
    listeners.delete(cb);
  };
}

function setModeInternal(next: ThemeMode) {
  if (!cachedState) {
    init();
    if (!cachedState) return;
  }
  if (next === cachedState.mode) return;
  const nextEffective = computeEffective(next, systemPreference);
  cachedState = { mode: next, effective: nextEffective };
  persistMode(next);
  withTransition(() => applyDom(nextEffective));
  emit();
}

export function useTheme() {
  const state = useSyncExternalStore(subscribe, getSnapshot, getServerSnapshot);

  const setMode = useCallback((next: ThemeMode) => {
    setModeInternal(next);
  }, []);

  const cycleMode = useCallback(() => {
    const order: ThemeMode[] = ["light", "dark", "system"];
    const current = cachedState?.mode ?? "system";
    const idx = order.indexOf(current);
    const next = order[(idx + 1) % order.length];
    setModeInternal(next);
  }, []);

  const toggleTheme = useCallback(() => {
    const current = cachedState?.effective ?? "light";
    setModeInternal(current === "dark" ? "light" : "dark");
  }, []);

  return {
    mode: state.mode,
    theme: state.effective,
    effective: state.effective,
    isDark: state.effective === "dark",
    setMode,
    cycleMode,
    toggleTheme,
  };
}
