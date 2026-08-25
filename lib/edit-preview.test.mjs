import assert from "node:assert/strict";
import { mkdtemp, mkdir, readFile, stat, writeFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";

const { previewEdit, previewWrite, PreviewError, PREVIEW_SOURCE_MAX_BYTES } = await import("./edit-preview.ts");

async function makeWorkspace() {
  return mkdtemp(path.join(tmpdir(), "pi-edit-preview-"));
}

test("edit preview produces a unified patch with context and does not mutate the file", async () => {
  const before = "line one\nline two\nline three\nline four\nline five\nline six\n";
  const dir = await makeWorkspace();
  await writeFile(path.join(dir, "demo.txt"), before, "utf8");
  try {
    const { patch } = await previewEdit({
      cwd: dir,
      filePath: "demo.txt",
      edits: [{ oldText: "line three", newText: "LINE THREE" }],
    });
    assert.match(patch, /^-line three$/m);
    assert.match(patch, /^\+LINE THREE$/m);
    // Surrounding context lines (±4) must be present.
    assert.match(patch, /^ line two$/m);
    assert.match(patch, /^ line four$/m);
    assert.equal(await readFile(path.join(dir, "demo.txt"), "utf8"), before);
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});

test("edit preview supports multiple disjoint edits in one pass", async () => {
  const before = "alpha\nbeta\ngamma\ndelta\nepsilon\n";
  const dir = await makeWorkspace();
  await writeFile(path.join(dir, "demo.txt"), before, "utf8");
  try {
    const { patch } = await previewEdit({
      cwd: dir,
      filePath: "demo.txt",
      edits: [
        { oldText: "alpha", newText: "ALPHA" },
        { oldText: "delta", newText: "DELTA" },
      ],
    });
    assert.match(patch, /^\+ALPHA$/m);
    assert.match(patch, /^\+DELTA$/m);
    assert.equal(await readFile(path.join(dir, "demo.txt"), "utf8"), before);
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});

test("edit preview rejects when oldText is not found", async () => {
  const dir = await makeWorkspace();
  await writeFile(path.join(dir, "demo.txt"), "alpha\nbeta\n", "utf8");
  try {
    await assert.rejects(
      previewEdit({ cwd: dir, filePath: "demo.txt", edits: [{ oldText: "missing", newText: "x" }] }),
      /Could not find/,
    );
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});

test("edit preview rejects ambiguous (non-unique) oldText", async () => {
  const dir = await makeWorkspace();
  await writeFile(path.join(dir, "demo.txt"), "dup\ndup\n", "utf8");
  try {
    await assert.rejects(
      previewEdit({ cwd: dir, filePath: "demo.txt", edits: [{ oldText: "dup", newText: "x" }] }),
      /must be unique/,
    );
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});

test("edit preview rejects no-op edits", async () => {
  const dir = await makeWorkspace();
  await writeFile(path.join(dir, "demo.txt"), "alpha\nbeta\n", "utf8");
  try {
    await assert.rejects(
      previewEdit({ cwd: dir, filePath: "demo.txt", edits: [{ oldText: "alpha", newText: "alpha" }] }),
      Error,
    );
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});

test("write preview patches an existing file without mutating it", async () => {
  const before = "a\nb\nc\nd\ne\nf\ng\n";
  const dir = await makeWorkspace();
  await writeFile(path.join(dir, "demo.txt"), before, "utf8");
  try {
    const { patch } = await previewWrite({
      cwd: dir,
      filePath: "demo.txt",
      content: "a\nB\nc\nd\ne\nf\ng\n",
    });
    assert.match(patch, /^-b$/m);
    assert.match(patch, /^\+B$/m);
    assert.match(patch, /^ d$/m); // context line
    assert.equal(await readFile(path.join(dir, "demo.txt"), "utf8"), before);
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});

test("write preview treats a missing file as a new file (all additions, nothing created)", async () => {
  const dir = await makeWorkspace();
  try {
    const { patch } = await previewWrite({
      cwd: dir,
      filePath: "sub/new.txt",
      content: "hello\nworld\n",
    });
    assert.match(patch, /@@ -0,0 \+1,2 @@/);
    assert.match(patch, /^\+hello$/m);
    assert.match(patch, /^\+world$/m);
    // No removal lines (the --- header line does not count).
    const removedLines = patch.split("\n").filter((line) => /^-(?!--)/.test(line));
    assert.equal(removedLines.length, 0);
    await assert.rejects(stat(path.join(dir, "sub/new.txt")));
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});

test("oversized source files are rejected before reading", async () => {
  const dir = await makeWorkspace();
  await writeFile(path.join(dir, "big.txt"), "x".repeat(PREVIEW_SOURCE_MAX_BYTES + 1));
  try {
    await assert.rejects(
      previewWrite({ cwd: dir, filePath: "big.txt", content: "y" }),
      (err) => err instanceof PreviewError && err.status === 413 && /too large to preview/.test(err.message),
    );
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});

test("edit preview rejects a missing file with a 404", async () => {
  const dir = await makeWorkspace();
  try {
    await assert.rejects(
      previewEdit({ cwd: dir, filePath: "missing.txt", edits: [{ oldText: "a", newText: "b" }] }),
      (err) => err instanceof PreviewError && err.status === 404 && /not found/i.test(err.message),
    );
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});

test("write preview rejects a directory target", async () => {
  const dir = await makeWorkspace();
  await mkdir(path.join(dir, "subdir"));
  try {
    await assert.rejects(
      previewWrite({ cwd: dir, filePath: "subdir", content: "x" }),
      (err) => err instanceof PreviewError && err.status === 400 && /directory/i.test(err.message),
    );
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});

test("edit validation failures are 422 PreviewErrors with the SDK message", async () => {
  const dir = await makeWorkspace();
  await writeFile(path.join(dir, "demo.txt"), "alpha\nbeta\n", "utf8");
  try {
    await assert.rejects(
      previewEdit({ cwd: dir, filePath: "demo.txt", edits: [{ oldText: "missing", newText: "x" }] }),
      (err) => err instanceof PreviewError && err.status === 422 && /Could not find/.test(err.message),
    );
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});
