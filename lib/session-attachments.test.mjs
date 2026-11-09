import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { createJiti } from "jiti";

const jiti = createJiti(import.meta.url);
const {
  deleteSessionAttachments,
  getSessionAttachmentsDir,
  safeAttachmentName,
  saveSessionAttachments,
} = await jiti.import("./session-attachments.ts");

const SESSION = "0b7e6f3c-1d2a-4c55-9e1f-2a3b4c5d6e7f";

function tempAgentDir(t) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "pi-web-attachments-test-"));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  return dir;
}

test("safe names keep letters, digits, dots, dashes; every other run becomes one underscore", () => {
  assert.equal(safeAttachmentName("my document (1).pdf"), "my_document_1_.pdf");
  assert.equal(safeAttachmentName("fattura è già.pdf"), "fattura_è_già.pdf");
  assert.equal(safeAttachmentName("../../etc/passwd"), "passwd");
  assert.equal(safeAttachmentName("C:\\Users\\x\\a b.txt"), "a_b.txt");
  assert.equal(safeAttachmentName(".env"), ".env");
  assert.equal(safeAttachmentName(""), "file");
  const long = safeAttachmentName(`${"x".repeat(300)}.pdf`);
  assert.equal(long.length, 100);
  assert.ok(long.endsWith(".pdf"));
});

test("saving writes each file under a 6-hex prefix in the session's folder", (t) => {
  const agentDir = tempAgentDir(t);
  const result = saveSessionAttachments(SESSION, [
    { name: "notes.txt", bytes: Buffer.from("hello") },
    { name: "notes.txt", bytes: Buffer.from("second") },
  ], agentDir);
  const dir = path.join(agentDir, "pi-web-attachments", SESSION);
  assert.equal(result.dir, dir + path.sep);
  assert.equal(result.files.length, 2);
  for (const file of result.files) {
    assert.equal(path.dirname(file.path), dir);
    assert.match(path.basename(file.path), /^[0-9a-f]{6}_notes\.txt$/);
    assert.equal(file.name, "notes.txt");
  }
  assert.notEqual(result.files[0].path, result.files[1].path);
  assert.equal(fs.readFileSync(result.files[0].path, "utf8"), "hello");
  assert.equal(fs.readFileSync(result.files[1].path, "utf8"), "second");
  assert.equal(result.files[1].size, 6);
  if (process.platform !== "win32") {
    assert.equal(fs.statSync(dir).mode & 0o777, 0o700);
    assert.equal(fs.statSync(result.files[0].path).mode & 0o777, 0o600);
  }
});

test("a session id that is not a uuid never names a folder", (t) => {
  const agentDir = tempAgentDir(t);
  assert.throws(() => getSessionAttachmentsDir("../outside", agentDir));
  assert.throws(() => saveSessionAttachments("..", [{ name: "a", bytes: Buffer.from("") }], agentDir));
});

test("deleting removes the session's folder and tolerates a missing one", (t) => {
  const agentDir = tempAgentDir(t);
  saveSessionAttachments(SESSION, [{ name: "a.txt", bytes: Buffer.from("a") }], agentDir);
  deleteSessionAttachments(SESSION, agentDir);
  assert.equal(fs.existsSync(path.join(agentDir, "pi-web-attachments", SESSION)), false);
  deleteSessionAttachments(SESSION, agentDir);
  deleteSessionAttachments("not-a-session", agentDir);
});
