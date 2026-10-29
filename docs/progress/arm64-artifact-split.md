# arm64-v8a in its own artifact (owner request, 2026-10-28)

**Why:** the arm64 TEST-ONLY APK (`TEST-ONLY-debugkey-app-release-arm64-v8a.apk`) is
inside the `opencode-android-smoke-test-only` artifact together with the smoke APK,
the androidTest APK, the universal APK and the screenshots. To get the arm64 build the
owner had to download all of them.

**Status (2026-10-29):** the repo copy `phase10/workflow/phase10-release.yml` HAS the change
(step 3 below, applied and YAML-checked). The live `.github/workflows/phase10-release.yml`
is NOT changed: the automation account cannot push `.github/workflows/*` (its token lacks
the `workflows` scope). Steps 1 and 2 are for the owner to paste into that file.
No shell script is changed by this patch; the APK name and location do not move.

## Change to `.github/workflows/phase10-release.yml`

1. In the step named "Upload the release-shaped smoke build + store screenshots",
   replace the `path:` block with exactly:

   ```yaml
          path: |
            phase10/out/smoke/
            phase10/out/screenshots/
            !phase10/out/smoke/TEST-ONLY-debugkey-app-release-arm64-v8a.apk
   ```

   (`!` exclusion is supported by `actions/upload-artifact@v4` in a multi-line `path`.)

2. Directly after that step, add:

   ```yaml
      - name: "Upload the arm64-v8a test-only APK on its own"
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: opencode-android-arm64-v8a-test-only
          path: phase10/out/smoke/TEST-ONLY-debugkey-app-release-arm64-v8a.apk
          if-no-files-found: ignore
   ```

3. Apply the same two edits to `phase10/workflow/phase10-release.yml` so the copy in the
   repo matches (that copy is the one the owner pastes from).

## Checks after the change

- Next CI run: the artifact list shows `opencode-android-arm64-v8a-test-only` with one
  file (about 109 MB at the last measurement), and `opencode-android-smoke-test-only`
  no longer contains the arm64 APK.
- The unsigned release artifact and the universal TEST-ONLY APK are unchanged.
