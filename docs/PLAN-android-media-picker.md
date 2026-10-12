# Plan: Android media picker — image attachments like pi-web

Goal: attach images in the native Android app exactly like the web composer —
picker, client-side compression, preview row, `images` on the prompt/steer
command, real thumbnails in the user bubble, and the model-modality warning.

Web reference behavior (what we mirror):

- `ChatInput.tsx`: paperclip button bottom-left of the composer →
  `<input type="file" accept="image/*" multiple>`; 56×56 preview thumbnails
  (object-fit cover, 6px radius, 1px border) with a × badge top-right.
- `compressImageFile()`: files >1MB and not GIF → downscale, JPEG q0.85,
  white background; keep the original if the result is not smaller.
- `lib/image-attachments.ts`: max 10 images, ≤10MB each (base64-decoded).
- Send: `images: [{type:"image", data, mimeType}]` on prompt/steer/followUp.
- Optimistic user message: content array `[text?, ...{type:"image",
  source:{type:"base64", media_type, data}}]`.
- User messages render thumbnails (max 240×240, contain) above the text.
- #584: warn (never block) when the selected model is known not to accept
  image input; unknown models stay silent.

Deviation agreed with Daniele: **max side 4096px** on Android (web uses 1024).

## Files

### 1. `android/.../data/ImageAttachments.kt` (new)

- `data class AttachedImage(val data: String, val mimeType: String)` —
  base64 without prefix.
- Constants: `MAX_IMAGES = 10`, `MAX_IMAGE_BYTES = 10MB`,
  `MAX_SIDE = 4096`, `JPEG_QUALITY = 85`, `COMPRESS_THRESHOLD = 1MB`.
- `suspend fun processImage(context: Context, uri: Uri): AttachedImage`
  (throws `IOException` with an API-safe message):
  - mime from `contentResolver.getType(uri)` (fallback `image/jpeg`).
  - GIF → raw bytes (keep animation, no decode).
  - Else, if size > 1MB: bounds-only decode → `inSampleSize` = smallest
    power of 2 ≥ max(w,h)/4096 (oversample so the decoded bitmap never
    exceeds ~4096²) → decode → `createScaledBitmap` if still >4096 →
    JPEG q85 on white background; keep the original bytes if the JPEG is
    not smaller.
  - Decode failure → raw bytes (mirrors the web's `catch → original`).
  - Validated against 10MB (base64-decoded length) → "Image too large
    (max 10MB)" otherwise.
- `fun validate(images: List<AttachedImage>): String?` — count + per-image
  size (base64 → decoded byte length).
- `fun toCommandJson(images): JsonArray` — `[{type:"image", data, mimeType}]`.
- `fun extractImages(content: JsonElement?): List<AttachedImage>` — read
  image blocks out of a user-message content array, supporting both the
  nested `source:{type:"base64", media_type, data}` and flat
  `{data, mimeType}` shapes (the web renders both).

### 2. `android/.../ui/chat/ChatViewModel.kt`

- `ChatUiState`: + `attachedImages: List<AttachedImage> = emptyList()`.
- `fun onImagesPicked(uris: List<Uri>)`: reserve against the 10-image cap
  (overflow → snackbar "A message can include at most 10 images"), process
  sequentially on `Dispatchers.IO` via `processImage`, append to state,
  per-image errors → snackbar. Context from `getApplication<Application>()`.
- `fun removeImage(index: Int)`.
- `send(text: String, images: List<AttachedImage> = emptyList())`:
  - allow blank text when images are present;
  - optimistic user message: content array with `text?` + nested image
    blocks (same shape as the web's optimistic message);
  - command body: + `images` field when non-empty (prompt and steer);
  - clear `attachedImages` only after the command succeeds — on rejection
    the images stay attached alongside `restoredDraft`.
- `ModelOption`: + `input: List<String> = emptyList()` (parsed from the
  `modelList` entries' `input` modalities).
- `fun modelSupportsImageInput(): Boolean` — web #584 rule: no selected
  model, no `input` list, or list contains `image` → true.

### 3. `android/.../ui/chat/ChatScreen.kt`

- `ChatScreen`: `rememberLauncherForActivityResult(
  ActivityResultContracts.PickMultipleVisualMedia(10))` →
  `vm.onImagesPicked(it)`. No permissions needed (photo picker).
- `Composer` + params `images`, `onPickImages`, `onRemoveImage`:
  - preview row above the text field: horizontally scrolling row of 56dp
    thumbnails (1dp border, 6dp radius) with a 16dp × badge (top/end −4dp),
    tap to remove;
  - attach button: 32dp icon button in the bottom row, before the model
    chip; tint accent when images are attached, `textTertiary` otherwise
    (mirrors the web);
  - `stopping = running && draft.isBlank() && images.isEmpty()`;
    `enabled = stopping || draft.isNotBlank() || images.isNotEmpty()`;
  - send → `vm.send(draft, images)`, then clear the draft.
- Modality warning: when `images.isNotEmpty() && !vm.modelSupportsImageInput()`
  show a small dismissible warning row above the composer (alert icon +
  "Model X does not accept images" + close), like the web banner; the
  dismissed flag resets when the images go back to zero.
- Call site: `onSend = { vm.send(draft, images); draft = "" }` with the
  composer state wired from `state.attachedImages`.

### 4. `android/.../data/ChatModel.kt`

- `ChatItem.User`: + `images: List<AttachedImage> = emptyList()` (filled
  via `ImageAttachments.extractImages` in `Messages.item()`).
- `Block.Image`: + `data: String? = null` (nested/flat extraction in
  `Messages.block()`).

### 4b. `android/.../ui/theme/Icons.kt`

- + `Image` line icon (frame + circle + mountain) and `Close` (×) icon.

### 5. `android/.../ui/chat/ChatItems.kt`

- New `Base64Image(image, cacheKey, modifier)`: async decode via
  `LaunchedEffect`/`Dispatchers.IO` (base64 → bytes → `BitmapFactory` with
  `inSampleSize` targeting ≤240dp·density), module-level `LruCache`
  (~16MB) keyed by the caller-provided stable key (message key + index);
  placeholder box while loading.
- `UserBubble`: thumbnail row above the text (max 240dp, `ContentScale.Fit`,
  6dp radius, 1dp border, 6dp gap), replacing the "1 image" label.
- `AssistantMessage`: `Block.Image` with data → `Base64Image`; without data
  keep the `[mimeType]` label.

## Notes / risks

- Photo picker: native on Android 13+; on 12− it rides the Play system
  update (API 21+). minSdk 26 is fine; the contract handles fallback.
- 4096px max side (vs web 1024) means bigger payloads: a 4096px JPEG q85
  is typically 2–5MB — within the 10MB cap, but the POST body grows.
- Peak decode memory is bounded by the power-of-2 oversample:
  ≤ ~4096²×4 ≈ 64MB for pathological inputs.
- No new dependencies: `activity-compose` (already present) provides the
  photo-picker contract; decoding uses `BitmapFactory`.
- Verify with `cd android && ./gradlew :app:compileDebugKotlin` (no
  emulator round-trip from here).

## Implementation notes (actual vs plan)

- `ActivityResultContracts` lives in `androidx.activity.result.contract`
  (activity 1.9.3) and `PickMultipleVisualMedia.launch()` takes the
  top-level `androidx.activity.result.PickVisualMediaRequest` — not the
  `ActivityResultContracts.PickVisualMedia` of older versions.
- `Modifier.defaultSize` does not exist in the pinned Compose (foundation
  1.7.6, split `foundation-layout` artifact); `Base64Image` takes a
  `placeholderSize: Dp = 96.dp` param and applies `Modifier.size(...)` only
  while the bitmap is still loading.
- `ViewModel.getApplication()` is not available in lifecycle 2.8.7 here, so
  `onImagesPicked(context, uris)` receives `LocalContext.current
  .applicationContext` from the screen (captured outside the launcher
  callback, which is not composable).
- Composer thumbnail cache keys include `image.data.hashCode()` so removals
  (which shift indices) cannot surface a stale cached bitmap.
- Verified with `./gradlew :app:assembleDebug` (green).
