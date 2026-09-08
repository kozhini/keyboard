# Souchastnik + Gemini Nano GitHub Actions build

Prepared against exact commit:

`df86f6ce02daf6daaad416c43839ca707a23e73d`

## Install

Copy these files into the root of your `MShverdiakov/souchastnik` checkout:

- `.github/workflows/build-gemini.yml`
- `patches/apply-gemini.py`
- `patches/README.md`

Then open GitHub Actions and run **Build Souchastnik with Gemini Nano**.

The workflow checks out the exact commit, applies the strict patch, builds
`assembleDebug`, verifies the APK exists, and uploads it as an artifact.

The workflow is intentionally a debug build: the repository's release signing
key is not available to CI and must not be put into the repository.
