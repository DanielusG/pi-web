import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const panelSource = await readFile(new URL("./SettingsPanel.tsx", import.meta.url), "utf8");
const shellSource = await readFile(new URL("./AppShell.tsx", import.meta.url), "utf8");
const themeSource = await readFile(new URL("../hooks/useTheme.ts", import.meta.url), "utf8");
const enSource = await readFile(new URL("../lib/i18n/messages/en.ts", import.meta.url), "utf8");
const zhSource = await readFile(new URL("../lib/i18n/messages/zh-CN.ts", import.meta.url), "utf8");

test("replaces the sidebar shortcuts with one settings entry", () => {
  assert.match(shellSource, /<SettingsPanel/);
  assert.match(shellSource, /translate\("common\.settings"\)/);
  assert.doesNotMatch(shellSource, /setModelsConfigOpen|setSkillsConfigOpen|setAgentsConfigOpen|setPluginsConfigOpen/);
});

test("keeps every requested configuration surface inside the settings panel", () => {
  for (const section of ["general", "models", "skills", "agents", "plugins"]) {
    assert.match(panelSource, new RegExp(`id: "${section}"`));
  }
  for (const component of ["ModelsConfig", "SkillsConfig", "AgentsConfig", "PluginsConfig"]) {
    assert.match(panelSource, new RegExp(`<${component} embedded`));
  }
});

test("restores the settings section and each list detail selection", async () => {
  assert.match(panelSource, /getLastSettingsSection\(cwd\)/);
  assert.match(panelSource, /setLastSettingsSection\(item\.id\)/);
  for (const name of ["ModelsConfig", "SkillsConfig", "AgentsConfig", "PluginsConfig"]) {
    assert.match(
      await readFile(new URL(`./${name}.tsx`, import.meta.url), "utf8"),
      /getLastSettingsSelection/,
    );
  }
});

test("offers direct light, dark, and system theme selection", () => {
  for (const preference of ["light", "dark", "auto"]) {
    assert.match(panelSource, new RegExp(`id: "${preference}"`));
  }
  assert.match(panelSource, /setThemePreference\(option\.id\)/);
  assert.match(themeSource, /const setThemePreference = useCallback/);
});

test("centers the sidebar settings label and keeps General free of divider rows", () => {
  assert.match(shellSource, /position: "relative", width: "100%"[\s\S]*?display: "grid", placeItems: "center"/);
  assert.match(shellSource, /aria-hidden="true" style=\{\{ position: "absolute", left: 10 \}\}/);
  assert.match(panelSource, /minHeight: 50[\s\S]*?display: "flex", alignItems: "center"/);
  assert.doesNotMatch(panelSource, /sections\.find\(\(item\) => item\.id === section\)/);
  assert.doesNotMatch(panelSource, /<section style=\{\{[^}]*borderBottom/);
  assert.doesNotMatch(panelSource, /borderLeft: index > 0/);
});

test("labels agent profiles as sub-agents", () => {
  assert.match(enSource, /"common\.agents": "Sub-agents"/);
  assert.match(enSource, /"agents\.new": "New sub-agent"/);
  assert.match(zhSource, /"common\.agents": "子代理"/);
  assert.match(zhSource, /"agents\.new": "新建子代理"/);
});
