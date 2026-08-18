import type { ToolEntry } from "@/lib/tool-presets";

type Translate = (key: string, params?: Record<string, string | number>) => string;

interface Props {
  loading: boolean;
  prompt: string | null;
  tools: ToolEntry[] | null;
  translate: Translate;
}

interface ParameterField {
  name: string;
  type: string;
  description?: string;
  required: boolean;
  allowedValues?: string;
  defaultValue?: string;
}

function formatValue(value: unknown): string {
  if (typeof value === "string") return value;
  if (value === undefined) return "";
  try {
    return JSON.stringify(value);
  } catch {
    return String(value);
  }
}

function formatSchemaType(schema: Record<string, unknown>): string {
  const variants = Array.isArray(schema.anyOf)
    ? schema.anyOf
    : Array.isArray(schema.oneOf)
      ? schema.oneOf
      : null;
  if (variants) {
    return variants
      .map((variant) => variant && typeof variant === "object"
        ? formatSchemaType(variant as Record<string, unknown>)
        : "unknown")
      .filter((value, index, values) => values.indexOf(value) === index)
      .join(" | ");
  }

  if (schema.const !== undefined) return formatValue(schema.const);
  if (Array.isArray(schema.enum) && schema.enum.length > 0 && schema.type === undefined) {
    return [...new Set(schema.enum.map((value) => value === null ? "null" : typeof value))].join(" | ");
  }

  const rawType = schema.type;
  const type = Array.isArray(rawType)
    ? rawType.filter((value): value is string => typeof value === "string").join(" | ")
    : typeof rawType === "string"
      ? rawType
      : typeof schema.$ref === "string"
        ? schema.$ref.split("/").pop() ?? "object"
        : "unknown";

  if (type === "array") {
    const items = schema.items;
    const itemType = items && typeof items === "object"
      ? formatSchemaType(items as Record<string, unknown>)
      : "unknown";
    return `${itemType}[]`;
  }
  return type;
}

export function getToolParameterFields(parameters?: Record<string, unknown>): ParameterField[] {
  if (!parameters || !parameters.properties || typeof parameters.properties !== "object") return [];
  const properties = parameters.properties as Record<string, unknown>;
  const required = new Set(
    Array.isArray(parameters.required)
      ? parameters.required.filter((value): value is string => typeof value === "string")
      : [],
  );

  return Object.entries(properties).map(([name, value]) => {
    const schema = value && typeof value === "object" ? value as Record<string, unknown> : {};
    return {
      name,
      type: formatSchemaType(schema),
      description: typeof schema.description === "string" ? schema.description : undefined,
      required: required.has(name),
      allowedValues: Array.isArray(schema.enum) ? schema.enum.map(formatValue).join(", ") : undefined,
      defaultValue: schema.default === undefined ? undefined : formatValue(schema.default),
    };
  });
}

function EmptyState({ children }: { children: string }) {
  return <div className="system-info-empty">{children}</div>;
}

export function SystemInfoPanel({ loading, prompt, tools, translate }: Props) {
  const activeTools = tools?.filter((tool) => tool.active) ?? null;

  return (
    <div className="system-info-panel">
      <section className="system-info-prompt" aria-label={translate("system.prompt")}>
        <div className="system-info-heading">{translate("system.prompt")}</div>
        <div className="system-info-scroll system-info-prompt-body">
          {prompt ? (
            <div className="system-info-prompt-text">{prompt}</div>
          ) : prompt === "" ? (
            <EmptyState>{translate("system.empty")}</EmptyState>
          ) : (
            <EmptyState>{loading ? translate("system.loading") : translate("system.load")}</EmptyState>
          )}
        </div>
      </section>

      <aside className="system-info-tools" aria-label={translate("system.tools")}>
        <div className="system-info-heading">
          <span>{translate("system.tools")}</span>
          {activeTools && (
            <span className="system-info-count">
              {translate("system.toolsCount", { count: activeTools.length })}
            </span>
          )}
        </div>
        <div className="system-info-scroll system-tool-list">
          {activeTools && activeTools.length > 0 ? activeTools.map((tool) => {
            const fields = getToolParameterFields(tool.parameters);
            return (
              <details className="system-tool" key={tool.name}>
                <summary className="system-tool-summary">
                  <code>{tool.name}</code>
                  <span>{translate("system.parameterCount", { count: fields.length })}</span>
                </summary>
                <div className="system-tool-body">
                  {tool.description && <div className="system-tool-description">{tool.description}</div>}
                  <div className="system-tool-section-label">{translate("system.parameters")}</div>
                  {fields.length > 0 ? (
                    <div className="system-tool-fields">
                      {fields.map((field) => (
                        <div className="system-tool-field" key={field.name}>
                          <div className="system-tool-field-name">
                            <code>{field.name}</code>
                            <span className={field.required ? "required" : undefined}>
                              {translate(field.required ? "system.required" : "system.optional")}
                            </span>
                          </div>
                          <div className="system-tool-field-value">
                            <code className="system-tool-type">{field.type}</code>
                            {field.description && <div>{field.description}</div>}
                            {field.allowedValues && (
                              <div className="system-tool-meta">
                                {translate("system.allowedValues")}: <code>{field.allowedValues}</code>
                              </div>
                            )}
                            {field.defaultValue !== undefined && (
                              <div className="system-tool-meta">
                                {translate("system.defaultValue")}: <code>{field.defaultValue}</code>
                              </div>
                            )}
                          </div>
                        </div>
                      ))}
                    </div>
                  ) : (
                    <div className="system-tool-no-parameters">{translate("system.noParameters")}</div>
                  )}
                  {tool.promptGuidelines && tool.promptGuidelines.length > 0 && (
                    <>
                      <div className="system-tool-section-label">{translate("system.guidelines")}</div>
                      <ul className="system-tool-guidelines">
                        {tool.promptGuidelines.map((guideline, index) => (
                          <li key={`${tool.name}:${index}`}>{guideline}</li>
                        ))}
                      </ul>
                    </>
                  )}
                </div>
              </details>
            );
          }) : activeTools ? (
            <EmptyState>{translate("system.noTools")}</EmptyState>
          ) : (
            <EmptyState>{loading ? translate("system.toolsLoading") : translate("system.toolsLoad")}</EmptyState>
          )}
        </div>
      </aside>

      <style>{`
        .system-info-panel {
          display: grid;
          grid-template-columns: minmax(0, 1fr) minmax(300px, 38%);
          height: min(600px, 75vh);
          min-height: 220px;
          background: var(--bg-panel);
          border-bottom: 1px solid var(--border);
        }
        .system-info-prompt,
        .system-info-tools {
          display: flex;
          min-width: 0;
          min-height: 0;
          flex-direction: column;
        }
        .system-info-tools {
          border-left: 1px solid var(--border);
          background: color-mix(in srgb, var(--bg-panel) 94%, var(--bg));
        }
        .system-info-heading {
          display: flex;
          min-height: 38px;
          align-items: center;
          justify-content: space-between;
          gap: 12px;
          padding: 0 16px;
          border-bottom: 1px solid var(--border);
          color: var(--text);
          font-size: 12px;
          font-weight: 600;
        }
        .system-info-count {
          color: var(--text-dim);
          font-size: 11px;
          font-weight: 400;
          white-space: nowrap;
        }
        .system-info-scroll {
          min-height: 0;
          overflow: auto;
        }
        .system-info-prompt-body {
          flex: 1;
          padding: 12px 16px;
        }
        .system-info-prompt-text {
          color: var(--text-muted);
          font-family: var(--font-mono);
          font-size: 12px;
          line-height: 1.6;
          white-space: pre-wrap;
          overflow-wrap: anywhere;
        }
        .system-info-empty {
          padding: 10px 0;
          color: var(--text-muted);
          font-size: 12px;
          font-style: italic;
        }
        .system-tool-list {
          flex: 1;
        }
        .system-tool-list > .system-info-empty {
          padding: 14px 16px;
        }
        .system-tool {
          border-bottom: 1px solid var(--border);
        }
        .system-tool-summary {
          display: flex;
          min-height: 42px;
          align-items: center;
          justify-content: space-between;
          gap: 12px;
          padding: 0 16px;
          color: var(--text);
          cursor: pointer;
          font-size: 12px;
        }
        .system-tool-summary:hover {
          background: var(--bg-hover);
        }
        .system-tool-summary code {
          color: var(--accent);
          font-size: 12px;
          font-weight: 600;
        }
        .system-tool-summary > span {
          margin-left: auto;
          color: var(--text-dim);
          font-size: 11px;
          white-space: nowrap;
        }
        .system-tool-summary::after {
          width: 6px;
          height: 6px;
          border-right: 1px solid var(--text-dim);
          border-bottom: 1px solid var(--text-dim);
          content: "";
          flex: 0 0 auto;
          transform: rotate(45deg);
          transition: transform 0.12s ease;
        }
        .system-tool[open] > .system-tool-summary::after {
          transform: rotate(225deg);
        }
        .system-tool-body {
          padding: 0 16px 14px;
        }
        .system-tool-description {
          padding: 2px 0 12px;
          color: var(--text-muted);
          font-size: 12px;
          line-height: 1.55;
          white-space: pre-wrap;
        }
        .system-tool-section-label {
          margin: 2px 0 7px;
          color: var(--text-dim);
          font-size: 11px;
          font-weight: 600;
        }
        .system-tool-fields {
          border-top: 1px solid var(--border);
        }
        .system-tool-field {
          display: grid;
          grid-template-columns: minmax(88px, 0.75fr) minmax(0, 1.5fr);
          gap: 12px;
          padding: 9px 0;
          border-bottom: 1px solid var(--border);
          font-size: 11px;
          line-height: 1.45;
        }
        .system-tool-field-name {
          display: flex;
          min-width: 0;
          flex-direction: column;
          gap: 3px;
          color: var(--text);
        }
        .system-tool-field-name code {
          overflow-wrap: anywhere;
        }
        .system-tool-field-name span {
          color: var(--text-dim);
          font-size: 10px;
        }
        .system-tool-field-name span.required {
          color: var(--accent);
        }
        .system-tool-field-value {
          min-width: 0;
          color: var(--text-muted);
          overflow-wrap: anywhere;
        }
        .system-tool-type {
          display: block;
          margin-bottom: 3px;
          color: var(--text);
        }
        .system-tool-meta {
          margin-top: 4px;
          color: var(--text-dim);
        }
        .system-tool-meta code {
          color: var(--text-muted);
        }
        .system-tool-no-parameters {
          padding: 1px 0 10px;
          color: var(--text-dim);
          font-size: 11px;
        }
        .system-tool-guidelines {
          margin: 0;
          padding-left: 18px;
          color: var(--text-muted);
          font-size: 11px;
          line-height: 1.5;
        }
        @media (max-width: 640px) {
          .system-info-panel {
            display: block;
            height: auto;
            min-height: 0;
          }
          .system-info-prompt-body,
          .system-tool-list {
            max-height: min(38vh, 320px);
          }
          .system-info-tools {
            border-top: 1px solid var(--border);
            border-left: none;
          }
        }
      `}</style>
    </div>
  );
}
