---
name: rebase-upstream
description: Rebase ave's fork branches onto upstream/master, resolve conflicts, rebuild the auto-build merge branch, and hand back push commands. Use when the sync-upstream workflow failed or ave asks to rebase/sync with upstream.
---

# Rebase fork branches onto upstream

This fork (`origin` = aveao/pebble-mobileapp) carries a set of feature branches on top of
`upstream/master` (coredevices/mobileapp). `auto-build` is a throwaway branch: `auto-build-base`
plus an octopus merge of every feature branch. GitHub Actions normally keeps `auto-build` fresh by
merging upstream into it; when that merge conflicts, ave runs this skill. Expect conflicts.

Never push. The SSH key prompts for a passphrase on every push, so ave pushes herself from the
commands you print at the end.

## Branches

Rebase in this order. Every branch rebases onto `upstream/master` (not onto `auto-build-base`).

| Branch | What it carries | Conflict hotspots |
|---|---|---|
| `auto-build-base` | CI workflows (`.github/workflows/auto-build.yml`, `sync-upstream.yml`) and `androidApp/build.gradle.kts` versionCode/signing tweaks | `androidApp/build.gradle.kts` whenever upstream touches versioning or signing |
| `feature/logviewer` | In-app log viewer screen, `PlatformShareLauncher.share` gained a mimetype parameter | `WatchSettingsScreen.kt`, `AppNavHost.kt`, `CommonRoutes.kt`; new upstream callers of `share(` need the mimetype argument |
| `feature/configexportimport` | Backup/restore (`coredevices.coreapp.backup.*`), adds `closeDatabase`/`getDatabasePath`/`getPbwCacheDirectory` to the `LibPebble` interface | `WatchSettingsScreen.kt`, `LibPebble.kt`, `FakeLibPebble.kt`, `LibPebbleModule.kt`; any new `LibPebble` implementation upstream needs the three methods. Also needs the coverage check below even when it rebases cleanly |
| `no-play-update-check` | Skip the Play in-app update check for non-Play installs | `util/src/androidMain/kotlin/AppUpdate.android.kt` |
| `mimetype-strictening` | Manifest intent filters accept `application/octet-stream` instead of `*/*` | `androidApp/src/main/AndroidManifest.xml` |
| `nohorts` | `cohorts.rebble.io` → `cohorts.lavate.ch` | `pebble/.../firmware/Cohorts.kt` |
| `imaging-compression` | DEFLATE'd pixel streams, ordered dithering, measured screen colours, timing logs | `libpebble3/.../imaging/*`, `PPoG.kt`, `Imaging.kt` packets, `NotificationImageStore.kt` |

When a branch is removed from this list (merged upstream, or dropped), also remove it from the
merge step and this table so the next run stays in sync.

## Procedure

### 0. Preflight

```
git status --porcelain          # must be clean apart from ignored/untracked build dirs
git fetch upstream
git fetch origin
```

Record the old base of the backup branch before anything moves; the coverage check needs it:

```
OLD_BASE=$(git merge-base upstream/master feature/configexportimport)
```

Also confirm each local branch matches `origin/<branch>` (`git rev-parse` both). If a local branch
is behind origin, fast-forward it first; if it is ahead, stop and ask ave which one is right.

### 1. Detect branches already merged upstream

For every branch, before rebasing:

```
git cherry upstream/master <branch>
```

Lines starting with `-` are commits whose patch already exists upstream. Also skim
`git log --oneline upstream/master@{1}..upstream/master` for commits that implement the same
feature in a different shape (search upstream for the branch's key identifiers, e.g.
`LogViewerScreen`, `BackupManager`, `cohorts.lavate.ch`, `RawDeflate`). A branch counts as merged
when, after the rebase, `git rev-list --count upstream/master..<branch>` is 0, or when upstream
has an equivalent implementation that makes the branch redundant.

Do not delete anything. Report merged or redundant branches at the end so ave can decide to drop
them from the merge list. A fully merged branch is a no-op in the octopus merge, so it is safe
to leave it in for this run.

### 2. Rebase each branch

```
git switch <branch>
GIT_EDITOR=true git rebase upstream/master
```

On conflict:

- Read both sides and the branch commit's intent (table above, plus `git show` on the commit being
  replayed). Resolve so the feature keeps working on top of the new upstream code. Do not just take
  one side.
- Prefer `git rebase --skip` only when the commit is verifiably already upstream (from step 1).
  Otherwise resolve, `git add`, `GIT_EDITOR=true git rebase --continue`.
- Keep each commit's diff minimal: no drive-by fixes while resolving.
- If upstream renamed or restructured something a branch depends on, follow the rename in the
  branch commit rather than adding a compatibility shim.

After each rebase, `git log --oneline upstream/master..<branch>` should list the same commits as
before (fewer if some were dropped as merged).

### 3. Coverage check for `feature/configexportimport`

Run this every time, even when the rebase applied cleanly. The backup covers five things;
upstream can extend any of them without touching the branch's files.

1. **Ring preferences** are mirrored by hand in `RingPreferencesExport` /
   `exportRingPreferences` / `restoreRingPreferences` in `BackupManager.kt`. Diff the interface:
   ```
   git diff $OLD_BASE upstream/master -- experimental/src/commonMain/kotlin/coredevices/ring/database/Preferences.kt
   ```
   For each new, renamed, or removed `val`/`set*` on `Preferences`, update the export data class,
   the export, and the restore. Include user-chosen settings. For device or bookkeeping state
   (pairing flags, sync indices, key fingerprints, backup counters) use judgement and list what
   you included or skipped in the report. Renames (e.g. `ringPaired` → `ringPairedOld`) must be
   followed on the branch, keeping the JSON field name stable so older `.pebblebak` files still
   restore.
2. **`CoreConfig`** (`util/.../CoreConfig.kt`) and **`LibPebbleConfig`**
   (`libpebble3/.../LibPebbleConfig.kt`) are serialised whole, so new fields come for free.
   Only check that both are still `@Serializable`, that nested new types are too, and that
   `coreConfigHolder.update` / `libPebble.updateConfig` still exist.
3. **Databases**: `dbFiles` in `BackupManager.kt` lists `coreapp.db`, `libpebble3.db`, and the
   ring Room db. Check for a new `@Database(` class or a new `"*.db"` filename upstream:
   ```
   git diff $OLD_BASE upstream/master --stat -- '*Database*.kt' '*Module*.kt'
   git grep -nE '"[A-Za-z0-9_-]+\.db"' upstream/master -- '*.kt'
   ```
   Add any new database (plus its `-wal`/`-shm` handling comes automatically) to `dbFiles` and the
   matching `DatabasePaths.*.kt` expect/actuals.
4. **PBW cache**: still `libPebble.getPbwCacheDirectory()`; confirm upstream has not moved where
   sideloaded apps are stored.
5. **Raw `Settings` keys** (`SHOWN_ONBOARDING`, `KEY_ENABLE_*_UPLOADS`, STT update keys, ...) are
   not backed up today. List any new ones upstream added in the report; only add them if ave has
   asked for them.

Commit coverage additions as a new commit on `feature/configexportimport` (message like
`Back up <thing>`), not squashed into an existing commit.

### 4. Rebuild `auto-build`

```
git switch auto-build
git reset --hard auto-build-base
git merge --no-edit feature/logviewer feature/configexportimport no-play-update-check mimetype-strictening nohorts imaging-compression
```

The octopus strategy refuses to run if any pair conflicts. If it aborts, merge the branches one at
a time in the same order, resolving each conflict with the same care as in step 2 (these are
cross-feature conflicts, typically `WatchSettingsScreen.kt` between logviewer and
configexportimport). Do not resolve conflicts by editing a feature branch; fix them in the merge
commit on `auto-build`.

### 5. Verify

If any conflict touched Kotlin or Gradle files, build `auto-build`:

```
./gradlew :androidApp:assembleDebug
```

If only `.yml`, manifest, or string-only changes were involved, a build is optional. Report
build failures verbatim; do not paper over them.

### 6. Report and hand off

End with:

1. Per branch: clean rebase / conflicts resolved (which files) / commits dropped as already
   upstream.
2. Branches that now appear merged or redundant upstream, with the upstream commit that did it.
3. Coverage changes made to `feature/configexportimport`, plus anything deliberately skipped.
4. Build result.
5. Push commands. One command with all refspecs needs a single passphrase entry:

```
git push -f origin auto-build-base feature/logviewer feature/configexportimport no-play-update-check mimetype-strictening nohorts imaging-compression auto-build
```

   and the per-branch form for when she wants to push selectively:

```
git push -f origin auto-build-base
git push -f origin feature/logviewer
...
git push -f origin auto-build
```

Pushing `auto-build` triggers the auto-build workflow on GitHub.
