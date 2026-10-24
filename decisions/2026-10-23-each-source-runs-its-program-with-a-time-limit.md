# Each source runs its program, found on the PATH it is given, with a time limit

> **Changed in part** by [sops is the one source of secrets; 1Password is removed](2026-10-24-sops-is-the-one-source-1password-is-removed.md) (2026-10-24): the `:1password` source is gone.

> **Changed in part** by [The family and its composer are groundwork](https://github.com/p4ulcristian/groundwork/blob/Starting-from-the-bottom/decisions/2026-10-23-the-family-and-its-composer-are-groundwork.md) (2026-10-23): manor, the family, and house, the composer, are both named groundwork now.

**Decision:** sops and op are programs, not libraries: safe finds each on the `PATH` of the environment `load` is given (`:env`), runs it by its absolute path, and stops it, by its own process, after the source's `:timeout-ms`, a required key: a run that takes longer is a problem at `[:secrets]` (sops) or at the secret's path (op). No program on the `PATH` is a problem at `[:secrets :from]`.

- **sops decrypts once**, the whole file; every reference is then looked up in the tree, so every missing name is listed.
- **1Password is read once per secret**, `op read`, one after another; each that fails is a problem at its path, with op's reason.

**Why:** a start that waits forever on the network (1Password) or on a stuck program is worse than one that fails and says so; how long is a choice the app writes out, by manor's rule. `op read` per secret is what op documents for one value; each read fails alone, so every missing secret is named at once. Running the binaries keeps safe free of age, sops's formats and 1Password's SDK.

**The price:** a 1Password start runs op once per secret, one after another, and each run counts against the service account's rate limit.

**Instead of:**
- `op inject`, one call for every secret: it stops at the first that fails and writes a template's text, so every value needs a delimiter it can't contain
- `op item get <namespace> --format json`, one call per library: the field is matched by label across sections, and a missing field must be told from a concealed one
- a Java age and sops reader: a format of sops's to follow by hand, with no review behind it
- no time limit: a start hung on the network with nothing said
