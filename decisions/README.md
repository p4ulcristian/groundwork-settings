# safe decisions

One file per decision: `YYYY-MM-DD-what-we-decided.md`. The title is the decision. Each file says what we decided, why, and what we chose against. A decision changes by a new file; the old one says so at the top (`Replaced by` or `Changed in part by`), and nothing else in it is rewritten.

safe is manor's settings and secrets (manor: house and the libraries it puts together). The design was decided with the user before any code; the rest came as v0.1.0 was built.

- [safe is its own library](2026-10-23-safe-is-its-own-library.md)
- [One EDN settings file per environment](2026-10-23-one-edn-file-per-environment.md)
- [Secrets are references, read from one source the file names](2026-10-23-secrets-are-references-read-from-one-named-source.md)
- [A secret stays wrapped until it is used](2026-10-23-a-secret-stays-wrapped-until-it-is-used.md)
- [Nothing decrypted is written to disk](2026-10-23-nothing-decrypted-is-written-to-disk.md)
- [Each source runs its program, found on the PATH it is given, with a time limit](2026-10-23-each-source-runs-its-program-with-a-time-limit.md)
- [#env fills in what the platform hands in](2026-10-23-env-fills-what-the-platform-hands-in.md)
- [load lists every problem at once, and logs nothing](2026-10-23-load-lists-every-problem-and-logs-nothing.md)
