import assert from "node:assert/strict";
import test from "node:test";

async function loadSubject() {
  return import("./word-diff.ts");
}

const oldTexts = (diff) => diff.old.map((s) => s.text).join("");
const newTexts = (diff) => diff.new.map((s) => s.text).join("");
const changedTexts = (segments) =>
  segments.filter((s) => s.changed).map((s) => s.text).join("");

test("highlights only the changed word in an otherwise identical pair", async () => {
  const { inlineDiff } = await loadSubject();
  const diff = inlineDiff('const value = "old";', 'const value = "new";');

  assert.deepEqual(diff, {
    old: [
      { text: 'const value = "', changed: false },
      { text: "old", changed: true },
      { text: '";', changed: false },
    ],
    new: [
      { text: 'const value = "', changed: false },
      { text: "new", changed: true },
      { text: '";', changed: false },
    ],
  });
});

test("keeps inline highlight for a small one-sided insertion", async () => {
  const { inlineDiff } = await loadSubject();
  const diff = inlineDiff("const a = 1;", "const a = 1 + 2;");

  assert.deepEqual(diff.old, [{ text: "const a = 1;", changed: false }]);
  assert.equal(newTexts(diff), "const a = 1 + 2;");
  assert.equal(changedTexts(diff.new), " + 2");
});

test("highlights only the changed character within a number", async () => {
  const { inlineDiff } = await loadSubject();
  const diff = inlineDiff("if (x > 10) {", "if (x > 20) {");

  assert.equal(oldTexts(diff), "if (x > 10) {");
  assert.equal(newTexts(diff), "if (x > 20) {");
  assert.equal(changedTexts(diff.old), "1");
  assert.equal(changedTexts(diff.new), "2");
});

test("handles unicode word characters", async () => {
  const { inlineDiff } = await loadSubject();
  const diff = inlineDiff("const café = 1;", "const cafe = 1;");

  assert.equal(oldTexts(diff), "const café = 1;");
  assert.equal(newTexts(diff), "const cafe = 1;");
  assert.equal(changedTexts(diff.old), "é");
  assert.equal(changedTexts(diff.new), "e");
});

test("returns null for identical or one-sided text", async () => {
  const { inlineDiff } = await loadSubject();

  assert.equal(inlineDiff("same", "same"), null);
  assert.equal(inlineDiff("", "x"), null);
  assert.equal(inlineDiff("x", ""), null);
});

test("returns null for lines above the length cap", async () => {
  const { inlineDiff } = await loadSubject();

  assert.equal(inlineDiff("a".repeat(501), "a".repeat(501) + "b"), null);
});

test("returns null when the change covers more than half of the lines", async () => {
  const { inlineDiff } = await loadSubject();

  assert.equal(inlineDiff("aaaa bbbb cccc", "xxxx yyyy zzzz"), null);
});
