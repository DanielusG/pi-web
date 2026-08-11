# Release Checklist

This repo publishes two artifacts for each release, both produced by GitHub Actions on tag push:

- npm package: `@axello/pi-web`
- GitHub Release: `AxelWT/pi-web`

**Never run `npm publish` or `npm run build` locally.** The CI workflow (`.github/workflows/release.yml`) is the only publishing path. Local work is limited to bumping the version, tagging, and pushing.

## Prerequisites (one-time)

- `NPM_TOKEN` is set as a repository secret in `AxelWT/pi-web` (Settings → Secrets and variables → Actions). The token needs publish permission for `@axello/pi-web`.
- The workflow file `.github/workflows/release.yml` exists on `main`.

## 1. Preflight

Run from a clean `main` checkout.

```bash
git status --short --branch          # clean, on main, up to date with origin
git log --oneline --decorate -5
gh auth status
node -e "const p=require('./package.json'); console.log(p.version)"
```

Confirm the next version number is not already published:

```bash
npm view @axello/pi-web versions --json --registry https://registry.npmjs.org/
```

Confirm the tag does not already exist:

```bash
git ls-remote --tags origin v<version>
gh release view v<version> --repo AxelWT/pi-web
```

## 2. Bump Version, Commit, and Tag

`npm version` updates `package.json` and `package-lock.json`, creates a commit, and creates a tag in one step. Do not pass `--no-git-tag-version` here — the tag is what triggers CI.

```bash
npm version patch -m "Release v%s"          # or: minor, major, or a specific version like 0.9.1
git push origin main --tags
```

That is the entire local side. Pushing the `v*` tag triggers `.github/workflows/release.yml`, which runs `npm ci`, `npm run build`, `npm publish --access public`, and `gh release create` with auto-generated notes.

## 3. Watch the Workflow

```bash
gh run watch
gh release view v<version> --repo AxelWT/pi-web
```

If the workflow fails, fix the issue and re-run the failed job from the Actions UI. Do not delete and re-push the same tag unless you also unpublish the failed npm version first (npm does not allow republishing the same version).

## 4. Generate or Edit Release Notes

The workflow creates the GitHub Release with `--generate-notes`. To replace the auto-generated notes with a hand-written bilingual summary, write them from the commits since the previous tag:

```bash
git log --oneline --decorate v<previous>..v<version>
git log --format='%h%x09%s%n%b' v<previous>..v<version>
git diff --stat v<previous>..v<version>
```

Then edit the release:

```bash
gh release edit v<version> --repo AxelWT/pi-web --notes-file - <<'EOF'
## 中文

基于 `v<previous>..v<version>` 的提交整理。

### 新增

- ...

### 修复

- ...

### 改进

- ...

### 内部调整

- 发布 npm 包 `@axello/pi-web@<version>`。

## English

Prepared from commits in `v<previous>..v<version>`.

### Added

- ...

### Fixed

- ...

### Improved

- ...

### Internal

- Published npm package `@axello/pi-web@<version>`.
EOF
```

## 5. Final Verification

```bash
gh release view v<version> --repo AxelWT/pi-web
npm view @axello/pi-web@<version> version --registry https://registry.npmjs.org/
git status --short --branch
git log --oneline --decorate -3
```

Expected:

- GitHub Release exists and is not a draft.
- npm exact version resolves.
- `main` is aligned with `origin/main`.
- `HEAD` points at the release commit and the `v<version>` tag.
