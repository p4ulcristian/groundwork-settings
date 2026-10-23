# safe is its own library

> **Changed in part** by [The family and its composer are groundwork](https://github.com/p4ulcristian/groundwork/blob/Starting-from-the-bottom/decisions/2026-10-23-the-family-and-its-composer-are-groundwork.md) (2026-10-23): manor, the family, and house, the composer, are both named groundwork now.

**Decision:** manor's settings and secrets are a library of their own, safe (the manor's wall safe), in its own public repository under the MIT license, taken from git by tag. One public namespace, `safe.api`, five names: `load`, `secret` (a malli shape), `reveal`, `equal?`, `make-secret`. It needs only malli and jsonista. No manor library depends on another for it: a library's settings shape names `safe/secret` and calls `safe/reveal` where the text is used; the app (house) calls `safe/load` once, at the start, and hands each library its part.

**Why:** every app reads its settings and its secrets the same way, before anything else runs, and every library that holds a secret (cellar's password, postbox's tokens, attic's key, meter's and house's bearer tokens) needs the same wrapper. In house it would be one more folder every library reaches into; on its own it is one small dependency each takes, and it can be read and tested alone. "[The library does not need sops](https://github.com/p4ulcristian/house/blob/main/decisions/2026-10-12-the-library-does-not-need-sops.md)" still holds for the other libraries: none of them reads a file or runs sops; they take values, now wrapped.

**Instead of:**
- settings and secrets inside house: every library that holds a secret would depend on house for its type
- aero: its tags, profiles and include rules are more than one file per environment needs, and it doesn't keep a secret from printing
- each library reading its own secret: one source of secrets per library, and every library touching files and programs
