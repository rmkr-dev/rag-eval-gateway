# Contributing

1. Branch from `main` and open a pull request. Nothing is pushed to `main` directly.
2. Run `mvn verify` locally. CI must be green before merge.
3. Add an entry under `Unreleased` in `CHANGELOG.md` for user-visible changes.
4. Keep secrets out of config files and tests. Use environment variables or Key Vault.
