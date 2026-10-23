# #env fills in what the platform hands in

**Decision:** `#env "PORT"` is the environment variable's text; `#env ["PORT" :int]` a whole number, `#env ["DEBUG" :boolean]` `true` or `false`, `#env ["NAME" :string]` the text again. A variable that isn't set, or whose text isn't its type, is a problem at its path, like any other; its text is never said. The environment is `load`'s required option `:env`: `(System/getenv)` in an app, a map in a test. It also gives sops and op their `PATH`.

**Why:** a few values come from the platform, not the repository: the port a host assigns, the token that opens the secrets (`:token-from #env "OP_SERVICE_ACCOUNT_TOKEN"`). Typed, the shape checks the number, not its text. An environment passed in keeps a test from setting the JVM's own, which it can't.

**Instead of:**
- `#env` with a default, `#env ["PORT" 8080]`: a value in effect that the file doesn't show when the variable is missing, against manor's rule
- the JVM's environment read inside `load`: tests couldn't give one, and where the values come from would be hidden
- `#env` for secrets: a secret handed in by environment variable is a later `:from`, so the reference stays `#secret`
