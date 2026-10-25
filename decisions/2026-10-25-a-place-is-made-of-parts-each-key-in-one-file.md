# A place is made of parts; each key is set in exactly one file

> The user's decision, 2026-10-25, planning Iron Rainbow's settings ("I want something modular"). Changes in part [One EDN settings file per environment](2026-10-23-one-edn-file-per-environment.md): the file `load` reads is a place, which lists its parts.

**Decision:** the settings file `load` is given is a place (where the app runs: a server, a developer's machine). It lists its parts under `:parts`, paths from the working directory. A part is a settings file for one thing (WorkOS with the sandbox account, the box's database), shared by every place that lists it, with its own sops file beside it.

- Every file says `:secrets` (`[]` for none); a place says `:parts` (`[]` for none); a part lists no parts.
- Each top-level key is set in exactly one file: a key in two files is a problem naming both.
- Every `#secret` is looked up in the sops files of every file of the place, each in exactly one; a sops file two parts name is read once.
- A problem in a part's key says which part.

**Why:** places share most of what they have: five development places use the same sandbox accounts. One file per place copies those values into every file, to drift; layered files (a base, then overrides) make where a value comes from depend on the order. Parts write each thing once, and a part's sops file holds only that thing's secrets, so who may read a secret is decided per thing.

**Instead of:**
- one complete file per place: shared values copied into each
- a base and overrides: the order decides, a forgotten override hides
- nested parts: a part of a part makes where a key comes from a search
- merging inside a key (two files each setting part of `:database`): the same overriding, one level down
