# A place lists the sops files it opens; each secret lives in exactly one

> The user's decision, 2026-10-24, planning Iron Rainbow's settings. Changes in part [Secrets are references, read from one source the file names](2026-10-23-secrets-are-references-read-from-one-named-source.md): `:secrets` is a vector of sops files, `[]` for none (was one source, or nil).

**Decision:** `:secrets` lists the sops files a place opens:

```clojure
{:secrets [{:from :sops :file "secrets/dev-vendors.sops.yaml" :age-key-file … :timeout-ms 30000}
           {:from :sops :file "secrets/box.sops.yaml" :age-key-file … :timeout-ms 30000}]}
```

- Each file is decrypted once. A `#secret` is read from the one file that has it.
- A secret found in two files is a problem at its path, naming both files: no file overrides another. One found in none names every file.
- A file listed twice is refused. A file that can't be read is the only problem said: what it holds is unknown, so nothing is called missing.

**Why:** a secret belongs to the thing it protects, and a sops file's recipients are who may read it. Secrets with different readers need different files: the team's sandbox keys, read by everyone, and one machine's database password, read by that machine. One file per place would copy the shared ones into every machine's file, to drift and to rotate many times.

**Instead of:**
- one file per place: shared secrets copied into each
- a later file overriding an earlier one (today's Iron Rainbow layering): where a value comes from depends on the order, and a leftover override hides silently
- one shared file for every dev machine: a machine's own database password readable by the whole team
