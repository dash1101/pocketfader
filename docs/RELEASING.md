# Releasing

1. Bump `version` in `pyproject.toml` and `__version__` in `pocketfader/__init__.py` (a test checks they agree), e.g. `0.1.0a5`.
2. Tag and push: `git tag -a v0.1.0-alpha.5 -m "Pocketfader v0.1.0-alpha.5" && git push origin v0.1.0-alpha.5`.
   The release workflow builds the Linux and Windows programs, the Python package and the Android apps, then publishes a pre-release.

## Signing the Android app

Android only installs an update over an installed app when both are signed with the **same key**. Create one key, once,
and keep it safe (if it is lost, existing installs must be uninstalled before a new key's app can be installed):

```bash
keytool -genkeypair -keystore pocketfader-release.jks -storetype PKCS12 -alias pocketfader \
        -keyalg RSA -keysize 4096 -validity 36500
```

Give it to the release workflow as repository secrets (Settings → Secrets and variables → Actions, or with the GitHub CLI):

```bash
base64 -w0 pocketfader-release.jks | gh secret set PF_KEYSTORE_B64
printf '%s' 'the-keystore-password' | gh secret set PF_KEYSTORE_PASSWORD
printf '%s' 'pocketfader' | gh secret set PF_KEY_ALIAS
```

Without the secrets the workflow still builds, but signs with a throwaway key and prints a warning; those APKs can't be
installed over an earlier build.
