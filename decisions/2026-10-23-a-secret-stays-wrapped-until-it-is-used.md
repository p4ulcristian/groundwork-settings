# A secret stays wrapped until it is used

**Decision:** a secret is a value of safe's own type, `safe.secret.Secret`, holding its name and its text. Everywhere it can be shown, it shows `#secret :postbox/postmark-token`:

- `pr`, `pr-str`, `print`, `println`, `pprint`, `*print-dup*`: its `print-method` and `print-dup`;
- `str`, `format "%s"`, string concatenation, any logger: its `toString`;
- JSON through Jackson (jsonista, cheshire): it is `JsonSerializable`, written as that text;
- `ex-message`, `ex-data`, malli's `explain` and `humanize`, logbook's lines: by the three above.

The text is a private field, so a serializer reading public fields finds only the name. `(safe/reveal x)` returns the text, where it is used and only there: a header, a password handed to the driver; it throws `:safe/not-a-secret` for anything else, naming only its class. `safe/secret` is the malli shape a library's settings shape names, `[:server-token safe/secret]`: it takes only a secret, so a token written out in a file is refused. `(safe/make-secret :errors/token "…")` makes one, for a library's tests.

A secret's `=` and `hash` are its identity: neither reads the text, so neither takes longer for a longer or closer one. A token a request carries is compared with `(safe/equal? secret given)`: both hashed with SHA-256, then `MessageDigest/isEqual`, in constant time whatever their length or where they first differ; nil is equal to nothing.

**Why:** a secret leaks through the paths no one reads for secrets: a settings map logged at the start, an exception's ex-data with the settings in it, a malli error carrying the value, a REPL printing a map. Wrapping makes each of those print the name; the text is reached only by a call that says so, which can be searched for. Equality by text would compare in time that depends on the text, and two settings maps would be equal or not by their secrets.

**The price:** each library calls `reveal` where it uses a secret, and its settings shape names `safe/secret`; its tests make secrets with `make-secret`. Two secrets with the same text are not `=`. The text is in the JVM's memory as a `String` until it is collected: wrapping keeps it out of print, not out of a heap dump.

**Instead of:**
- plain strings: every log of the settings is a leak
- a `:redacted` key list the logger knows: every logger, printer and error must know it, and a new key is unprotected until listed
- a char array wiped after use: Postmark's header, HikariCP and the AWS signer take strings
- `@secret` (IDeref): a deref is easy to write without noticing; `reveal` says what it does
