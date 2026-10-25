# Secrets are references, read from one source the file names

> **Changed in part** by [A place lists the sops files it opens; each secret lives in exactly one](2026-10-24-a-place-lists-its-sops-files-each-secret-in-one.md) (2026-10-24): `:secrets` is a vector of sops files.

> **Changed in part** by [sops is the one source of secrets; 1Password is removed](2026-10-24-sops-is-the-one-source-1password-is-removed.md) (2026-10-24): the `:1password` source is gone.

**Decision:** a secret in the settings file is a reference, `#secret :postbox/postmark-token`, a namespaced keyword. The file names one source under the top-level key `:secrets`, which `load` leaves out of the result; nil, written out, when the file has no secret. From the start there are two sources, every key required:

```clojure
{:from :sops :file "secrets/production.sops.yaml" :age-key-file "/run/secrets/age-key" :timeout-ms 30000}
{:from :1password :vault "shop-production" :token-from #env "OP_SERVICE_ACCOUNT_TOKEN" :timeout-ms 30000}
```

- **The name is found by its namespace and its name.** `:postbox/postmark-token` is, in sops, the key `postmark-token` under the key `postbox`; in 1Password, the field `postmark-token` of the item `postbox` in the vault: `op://shop-production/postbox/postmark-token`. Each library's secrets sit together in either.
- **A secret is text.** A sops value that isn't (a number, a map) is a problem at its path: write it quoted.
- **`:token-from` must be an `#env`** in the file: a service account's token written out is refused, named.
- **A new source is a new `:from`** (secret files, environment variables): the references stay as they are; only `:secrets` changes.

**Why both:** sops keeps the secrets encrypted in the app's own repository, versioned with the settings that use them; it needs only the sops binary and one age key on the machine, and works with no network and no account. 1Password keeps them out of the repository altogether, shared, rotated and audited in one place, with a service account that may read one vault; it needs `op` and the network. A small app, or one deployed where nothing else can be installed, takes sops; a team that already keeps its secrets in 1Password takes 1Password. A reference that names only the secret keeps the switch to one key in one file.

**Instead of:**
- the secret in the reference, `#secret "op://vault/item/field"` or `#sops [:postbox :token]`: every reference names its source, and moving to the other rewrites them all
- several sources in one file: a secret's origin depends on which source has it first
- the flat key `postbox/postmark-token` in sops: 1Password has no flat key, and the two would differ
- values of any type: a port or a flag isn't secret, and belongs in the file or in `#env`
