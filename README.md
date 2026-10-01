# DevDesk

[![build](https://github.com/LouayChayeb/devdesk/actions/workflows/build.yml/badge.svg)](https://github.com/LouayChayeb/devdesk/actions/workflows/build.yml)

DevDesk is a Linux-first desktop app and CLI for running local multi-service development environments.
It starts your services (native processes and containers) in dependency order, waits until they are healthy,
keeps their logs, stops exactly what it started, and, above all, **explains why something is broken**:
"Backend can't start: port 3001 is held by PID 4121 (`node`, not part of this project)."

> 🚧 **Work in progress.** Nothing usable yet. The project is at milestone M0 (repository skeleton). See [docs/ROADMAP.md](docs/ROADMAP.md).

## Build

Requires Linux and JDK 25.

```bash
./gradlew build          # compile, test, check formatting and style
./gradlew spotlessApply  # auto-format the code
./gradlew :cli:run       # run the CLI
```

## License

[Apache-2.0](LICENSE)
