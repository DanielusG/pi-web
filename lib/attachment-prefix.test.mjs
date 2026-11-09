import assert from "node:assert/strict";
import test from "node:test";
import { createJiti } from "jiti";

const jiti = createJiti(import.meta.url);
const {
  attachmentDisplayText,
  formatAttachmentPrefix,
  splitAttachmentPrefix,
  withAttachmentPrefix,
} = await jiti.import("./attachment-prefix.ts");

const DIR = "/home/u/.pi/agent/pi-web-attachments/0b7e6f3c-1d2a-4c55-9e1f-2a3b4c5d6e7f/";
const FILES = [
  { path: `${DIR}a1b2c3_text1.txt`, name: "text1.txt" },
  { path: `${DIR}9e8d7c_my_document_1_.pdf`, name: "my document (1).pdf" },
];

// The exact text the Android client's AttachmentPrefixTest expects too.
const EXPECTED = [
  `Attachments from the user (you should probably read them; Pi saved them in ${DIR}):`,
  `- ${DIR}a1b2c3_text1.txt (original name: text1.txt)`,
  `- ${DIR}9e8d7c_my_document_1_.pdf (original name: my document (1).pdf)`,
  "",
  "---",
  "",
  "Summarize these",
].join("\n");

test("the prefix lists each file with its original name, then the typed text", () => {
  assert.equal(withAttachmentPrefix(DIR, FILES, "Summarize these"), EXPECTED);
});

test("files alone send the list without a separator", () => {
  const text = withAttachmentPrefix(DIR, FILES, "");
  assert.equal(text, formatAttachmentPrefix(DIR, FILES));
  assert.deepEqual(splitAttachmentPrefix(text), { lead: "", dir: DIR, attachments: FILES, body: "" });
});

test("no attachments leave the text alone", () => {
  assert.equal(withAttachmentPrefix(DIR, [], "hello"), "hello");
});

test("split gives back the directory, the files and a multi-line body", () => {
  const text = withAttachmentPrefix(DIR, FILES, "line one\n\n---\n\nline two");
  assert.deepEqual(splitAttachmentPrefix(text), {
    lead: "",
    dir: DIR,
    attachments: FILES,
    body: "line one\n\n---\n\nline two",
  });
});

test("a newline in a device file name stays on its line", () => {
  const text = withAttachmentPrefix(DIR, [{ path: `${DIR}a1b2c3_a_b.txt`, name: "a\nb.txt" }], "x");
  assert.deepEqual(splitAttachmentPrefix(text)?.attachments, [{ path: `${DIR}a1b2c3_a_b.txt`, name: "a b.txt" }]);
});

test("a lead an extension put before the prefix is kept apart", () => {
  // e.g. a timestamp extension's input handler: "⏱ <time>" on its own line, or on the header's.
  for (const lead of ["⏱ 2026-11-09 20:45:12\n", "[20:45] "]) {
    assert.deepEqual(splitAttachmentPrefix(lead + EXPECTED), {
      lead,
      dir: DIR,
      attachments: FILES,
      body: "Summarize these",
    });
    assert.equal(attachmentDisplayText(lead + EXPECTED), `${lead}Summarize these`);
    assert.equal(attachmentDisplayText(lead + formatAttachmentPrefix(DIR, FILES)), `${lead}text1.txt, my document (1).pdf`);
  }
});

test("text that only looks like a prefix is not split", () => {
  assert.equal(splitAttachmentPrefix("Attachments from the user are below"), null);
  assert.equal(splitAttachmentPrefix(EXPECTED.replace("\n\n---\n\n", "\n---\n")), null);
  const header = EXPECTED.split("\n")[0];
  assert.equal(splitAttachmentPrefix(`${header}\nno list here`), null);
  assert.equal(splitAttachmentPrefix(`${header}\n- missing the original name`), null);
});

test("a list shows the typed text, or the names for files alone", () => {
  assert.equal(attachmentDisplayText(EXPECTED), "Summarize these");
  assert.equal(attachmentDisplayText(formatAttachmentPrefix(DIR, FILES)), "text1.txt, my document (1).pdf");
  assert.equal(attachmentDisplayText("plain message"), "plain message");
});
