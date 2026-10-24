# sops is the one source of secrets; 1Password is removed

> The user's decision, 2026-10-24, while planning Iron Rainbow's settings: 1Password had only been an example. Changes in part [Secrets are references, read from one source the file names](2026-10-23-secrets-are-references-read-from-one-named-source.md), [Each source runs its program, found on the PATH it is given, with a time limit](2026-10-23-each-source-runs-its-program-with-a-time-limit.md), [Nothing decrypted is written to disk](2026-10-23-nothing-decrypted-is-written-to-disk.md) and [#env fills in what the platform hands in](2026-10-23-env-fills-what-the-platform-hands-in.md).

**Decision:** the secrets come from sops alone: `{:from :sops :file … :age-key-file … :timeout-ms …}`. The `:1password` source, its `op read` per secret and the `:token-from` check are gone.

**Why:** an app reading its secrets from 1Password can't start when 1Password can't be reached, and each start costs one read per secret against the service account's limits (1Password's daily limit is per account: 1,000 requests on Families, 5,000 on Teams; over it, an hour of 429s), so a crash loop or a busy deploy day could lock the app out of its own secrets. sops needs nothing outside the machine: the encrypted file and one age key. A second source no app uses is code nobody runs.

**Instead of:**
- keeping 1Password as an option: it isn't production-ready for a start that must not depend on the network, and nothing uses it
- 1Password Connect (a self-hosted cache): one more service to keep alive for every start
