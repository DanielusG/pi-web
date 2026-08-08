/**
 * Unified syntax-highlighting theme that uses CSS variables for all colours.
 *
 * Because every colour resolves to a var(--syntax-*) variable defined in
 * globals.css, switching between light and dark requires **no React re-render**
 * of the SyntaxHighlighter — the CSS variables change on <html> and the browser
 * transitions every colour (background, text, tokens) in perfect sync via the
 * `.theme-transition *` transition rule.
 *
 * This eliminates the "shorthand vs longhand" React warning (no hardcoded
 * background/backgroundColor mixing) and makes theme switching feel holistic
 * instead of having code tokens lag behind the surrounding UI.
 */

export const SYNTAX_THEME = {
  'pre[class*="language-"]': {
    color: "var(--syntax-text)",
    fontSize: "13px",
    textShadow: "none",
    fontFamily: "var(--font-mono)",
    direction: "ltr",
    textAlign: "left",
    whiteSpace: "pre",
    wordSpacing: "normal",
    wordBreak: "normal",
    lineHeight: "1.5",
    MozTabSize: "4",
    OTabSize: "4",
    tabSize: "4",
    WebkitHyphens: "none",
    MozHyphens: "none",
    msHyphens: "none",
    hyphens: "none",
    padding: "1em",
    margin: ".5em 0",
    overflow: "auto",
    backgroundColor: "transparent",
  },
  'code[class*="language-"]': {
    color: "var(--syntax-text)",
    fontSize: "13px",
    textShadow: "none",
    fontFamily: "var(--font-mono)",
    direction: "ltr",
    textAlign: "left",
    whiteSpace: "pre",
    wordSpacing: "normal",
    wordBreak: "normal",
    lineHeight: "1.5",
    MozTabSize: "4",
    OTabSize: "4",
    tabSize: "4",
    WebkitHyphens: "none",
    MozHyphens: "none",
    msHyphens: "none",
    hyphens: "none",
  },

  /* selection */
  'pre[class*="language-"]::selection': {
    textShadow: "none",
    background: "var(--syntax-selection-bg)",
  },
  'code[class*="language-"]::selection': {
    textShadow: "none",
    background: "var(--syntax-selection-bg)",
  },
  'pre[class*="language-"] *::selection': {
    textShadow: "none",
    background: "var(--syntax-selection-bg)",
  },
  'code[class*="language-"] *::selection': {
    textShadow: "none",
    background: "var(--syntax-selection-bg)",
  },

  /* inline code */
  ':not(pre) > code[class*="language-"]': {
    padding: ".1em .3em",
    borderRadius: ".3em",
    color: "var(--syntax-inline-code)",
    background: "var(--syntax-inline-code-bg)",
  },

  /* tokens */
  comment: { color: "var(--syntax-comment)", fontStyle: "italic" },
  prolog: { color: "var(--syntax-comment)", fontStyle: "italic" },
  doctype: { color: "var(--syntax-comment)", fontStyle: "italic" },
  cdata: { color: "var(--syntax-cdata)" },
  namespace: { opacity: ".7" },

  string: { color: "var(--syntax-string)" },
  char: { color: "var(--syntax-string)" },
  builtin: { color: "var(--syntax-string)" },
  deleted: { color: "var(--syntax-deleted)" },

  punctuation: { color: "var(--syntax-punctuation)" },
  operator: { color: "var(--syntax-operator)" },
  "operator.arrow": { color: "var(--syntax-keyword)" },

  url: { color: "var(--syntax-url)" },
  symbol: { color: "var(--syntax-number)" },
  number: { color: "var(--syntax-number)" },
  boolean: { color: "var(--syntax-boolean)" },
  constant: { color: "var(--syntax-constant)" },
  variable: { color: "var(--syntax-variable)" },
  inserted: { color: "var(--syntax-inserted)" },
  unit: { color: "var(--syntax-number)" },

  atrule: { color: "var(--syntax-atrule)" },
  "atrule.rule": { color: "var(--syntax-control-flow)" },
  "atrule.url": { color: "var(--syntax-variable)" },
  "atrule.url.function": { color: "var(--syntax-function)" },
  "atrule.url.punctuation": { color: "var(--syntax-punctuation)" },

  keyword: { color: "var(--syntax-keyword)" },
  "keyword.module": { color: "var(--syntax-control-flow)" },
  "keyword.control-flow": { color: "var(--syntax-control-flow)" },

  "attr-value": { color: "var(--syntax-attr-value)" },
  "attr-value.punctuation": { color: "var(--syntax-attr-value)" },
  "attr-value.punctuation.attr-equals": { color: "var(--syntax-punctuation)" },

  function: { color: "var(--syntax-function)" },
  "function.maybe-class-name": { color: "var(--syntax-function)" },
  "maybe-class-name": { color: "var(--syntax-class-name)" },
  "class-name": { color: "var(--syntax-class-name)" },

  console: { color: "var(--syntax-variable)" },
  parameter: { color: "var(--syntax-variable)" },
  interpolation: { color: "var(--syntax-variable)" },
  "punctuation.interpolation-punctuation": { color: "var(--syntax-keyword)" },
  "imports.maybe-class-name": { color: "var(--syntax-variable)" },
  "exports.maybe-class-name": { color: "var(--syntax-variable)" },

  escape: { color: "var(--syntax-escape)" },

  tag: { color: "var(--syntax-tag)" },
  "tag.punctuation": { color: "var(--syntax-cdata)" },
  "attr-name": { color: "var(--syntax-attr-name)" },
  property: { color: "var(--syntax-attr-name)" },
  regex: { color: "var(--syntax-regex)" },
  entity: { color: "var(--syntax-entity)" },

  "doctype.doctype-tag": { color: "var(--syntax-keyword)" },
  "doctype.name": { color: "var(--syntax-variable)" },

  selector: { color: "var(--syntax-selector)" },
  important: { color: "var(--syntax-important)", fontWeight: "bold" },
  bold: { fontWeight: "bold" },
  italic: { fontStyle: "italic" },

  /* language-specific overrides */
  ".language-css .token.string.url": { textDecoration: "underline" },
  ".language-regex .token.anchor": { color: "var(--syntax-function)" },
  ".language-html .token.punctuation": { color: "var(--syntax-cdata)" },
  ".language-html .language-css .token.punctuation": { color: "var(--syntax-punctuation)" },
  ".language-html .language-javascript .token.punctuation": { color: "var(--syntax-punctuation)" },
  ".language-json .token.boolean": { color: "var(--syntax-keyword)" },
  ".language-json .token.number": { color: "var(--syntax-keyword)" },
  ".language-json .token.property": { color: "var(--syntax-class-name)" },
  ".language-autohotkey .token.selector": { color: "var(--syntax-keyword)" },
  ".language-autohotkey .token.tag": { color: "var(--syntax-deleted)" },
  ".language-autohotkey .token.keyword": { color: "var(--syntax-keyword)" },

  'pre[class*="language-javascript"]': { color: "var(--syntax-variable)" },
  'code[class*="language-javascript"]': { color: "var(--syntax-variable)" },
  'pre[class*="language-jsx"]': { color: "var(--syntax-variable)" },
  'code[class*="language-jsx"]': { color: "var(--syntax-variable)" },
  'pre[class*="language-typescript"]': { color: "var(--syntax-variable)" },
  'code[class*="language-typescript"]': { color: "var(--syntax-variable)" },
  'pre[class*="language-tsx"]': { color: "var(--syntax-variable)" },
  'code[class*="language-tsx"]': { color: "var(--syntax-variable)" },
  'pre[class*="language-css"]': { color: "var(--syntax-string)" },
  'code[class*="language-css"]': { color: "var(--syntax-string)" },
  'pre[class*="language-html"]': { color: "var(--syntax-text)" },
  'code[class*="language-html"]': { color: "var(--syntax-text)" },

  'pre[class*="language-"] > code[class*="language-"]': {
    position: "relative",
    zIndex: "1",
  },
} as const;
