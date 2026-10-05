# Broken on purpose

Each file is a tiny project with one typical problem, to see how DevDesk explains it.
Run them from this folder, for example:

```bash
devdesk why -f command-not-found.yaml      # checks before starting (no trust needed)
devdesk trust -f crash.yaml && devdesk up -f crash.yaml
```

| File | Problem | Rule |
|---|---|---|
| `port-conflict.yaml` | port 3003 already taken (first run `python3 -m http.server 3003` in another terminal) | port-conflict |
| `command-not-found.yaml` | a typo in the program name | program-missing |
| `crash.yaml` | the app exits with an error; a second service depends on it | crash + blocked |
| `crash-loop.yaml` | the app crashes again and again with `restart: on-failure` | crash (loop) |
| `wrong-port.yaml` | the app listens on 3005, the health check waits on 3006 | health |
| `missing-env.yaml` | `.env.example` lists settings nobody set | missing-env |
| `killed.yaml` | the app is SIGKILLed by something outside DevDesk (like the out-of-memory killer) | killed-from-outside |
| `connection-leak.yaml` | api opens a database connection every 2 s and never closes it. Wait 10 min, or watch `devdesk ps` | socket-growth |
