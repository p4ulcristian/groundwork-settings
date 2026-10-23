# Nothing decrypted is written to disk

**Decision:** safe never writes a secret to a file, caches nothing, and runs each program so that it writes nothing either:

- **sops** runs `sops --decrypt --output-type json <file>`; its standard output is read into memory and parsed there. It gets only `PATH` and `SOPS_AGE_KEY_FILE`: no `HOME`, so no default key file, and no `SOPS_AGE_KEY` of the shell's; the key it uses is the one the settings name.
- **op** runs `op read --no-newline op://…`; its standard output is the text. It gets only `PATH`, `OP_SERVICE_ACCOUNT_TOKEN` and `OP_CACHE=false`: no `HOME`, so no config folder and no cache is made, and no person's sign-in or desktop app is used, only the service account. The token goes in op's environment, never on its command line, where any process could read it.
- **Neither has standard input**, and what either prints on standard error is said in a problem only after its first line is trimmed and, for op, the token replaced by its name. sops's output that isn't JSON is never quoted: Jackson's parse errors quote the text they read.

**Why:** a decrypted file outlives the start that wrote it: in a backup, a container layer, a crash. sops and 1Password both decrypt to standard output, so there is no need for one. A program inheriting the app's environment could use a key or an account the settings don't name.

**The price:** each start runs sops once, or op once per secret; nothing is reused across starts. op without `HOME` was checked to start (it reaches the token's check); the tests run a fake op, not a real service account.

**Instead of:**
- `sops exec-file` or `op inject -o`: they write a file, and their docs say to delete it afterwards
- a cache of decrypted values: a file to protect, and a secret that stays after it is rotated
- the program inheriting the environment: the shell's sops key or a person's 1Password session could stand in for the named one
