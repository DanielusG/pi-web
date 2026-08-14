import coreWebVitals from "eslint-config-next/core-web-vitals";
import typescript from "eslint-config-next/typescript";

const eslintConfig = [
  ...coreWebVitals,
  ...typescript,
  {
    rules: {
      "react-hooks/immutability": "off",
      "react-hooks/refs": "off",
      "react-hooks/set-state-in-effect": "off",
    },
  },
  {
    // bin/pty-server.js is a CommonJS subprocess script — it intentionally
    // uses require() and runs outside Next.js. Skip TS/require lint rules.
    files: ["bin/pty-server.js"],
    languageOptions: {
      sourceType: "commonjs",
      parserOptions: { ecmaVersion: "latest" },
    },
    rules: {
      "@typescript-eslint/no-require-imports": "off",
      "@typescript-eslint/no-var-requires": "off",
    },
  },
];

export default eslintConfig;
