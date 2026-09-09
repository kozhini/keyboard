# Souchastnik + Gemini Nano GitHub Actions build

## Install

Copy these files into the root of your checkout:

- `.github/workflows/build-gemini.yml`
- `patches/apply-gemini.py`
- `patches/README.md`

Then open GitHub Actions and run **Build Souchastnik with Gemini Nano**.

The workflow checks out the exact commit, applies the strict patch, builds
`assembleDebug`, verifies the APK exists, and uploads it as an artifact.

The workflow is intentionally a debug build: the repository's release signing
key is not available to CI and must not be put into the repository.
