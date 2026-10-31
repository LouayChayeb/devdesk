# DevDesk

[![build](https://github.com/LouayChayeb/devdesk/actions/workflows/build.yml/badge.svg)](https://github.com/LouayChayeb/devdesk/actions/workflows/build.yml)
[![license: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)
![platform: Linux and WSL2](https://img.shields.io/badge/platform-Linux%20%7C%20WSL2-informational)
![status: early](https://img.shields.io/badge/status-early-orange)

**Runs your project's services, tells you why one broke, and leaves your machine clean.**

DevDesk is a desktop app and CLI for local multi-service development: native processes (`npm run dev`,
`uvicorn ...`) and containers (Postgres, Redis...) described in one `devdesk.yaml`. It starts them in dependency order,
waits for real health checks, keeps the logs, stops exactly what it started, and when something breaks, it shows the
evidence and the fix.

![DevDesk running a three-service project: db, api and web healthy, with their logs](.github/assets/services.png)

## Why DevDesk

- **It explains failures.** A busy port, a typo in a command, a health check on the wrong port, a crash, an empty
  secret, a database without its tables: each gets a plain explanation, the evidence it's based on, and the command
  that fixes it.
- **It cleans up after itself, and only after itself.** Every service runs in its own systemd scope (or a process
  group with a random marker), containers carry the project's label. Stopping stops every child and grandchild.
  Nothing is ever killed by name or by port. If DevDesk itself is killed, the next start finds what it left.
- **It can get someone else's project running.** Paste a GitHub link: DevDesk clones it, reads its compose file and
  Dockerfiles, asks before running anything, starts it, and later removes everything it made with one click.

## See it explain a failure

Port 3003 is already taken by a server someone started in another terminal. DevDesk names it and gives the command:

![A service fails to start because its port is taken; clicking Why? shows who holds the port and how to free it](.github/assets/why-port.gif)

The same kind of problem in a terminal (real output from the example project, `examples/full-stack`, with port 18000
taken by hand first):

```
$ devdesk why
Checking full-stack (nothing is started)...
gateway:
  ✗ Port 18000 is already in use
      why:  gateway needs port 18000, but it's taken by PID 144466 (python3 -m http.server 18000 --bind 127.0.0.1), which is not part of this project.
      seen: /proc/net/tcp: 127.0.0.1:18000 LISTEN, uid 1000 → PID 144466
      fix:  Free it with: kill 144466
      fix:  or change the port gateway uses in devdesk.yaml
1 problem(s) would stop the project from starting.
```

And a secret someone forgot to fill in (`JWT_SECRET= devdesk up`, shortened):

```
✗ auth crash loop: crashed 6 times within 2 minutes; not restarting it again
✗ web not started, because auth keeps crashing (5 restarts), last: exited with error code 1
✗ gateway not started, because web is blocked: auth keeps crashing (5 restarts), last: exited with error code 1
  ✗ auth's setting JWT_SECRET is empty
      why:  auth's error names it: JWT_SECRET is empty. Apps refuse to start, or fail on every request, without their secrets.
      seen: its error: auth: JWT_SECRET is empty, refusing to start: login tokens can't be signed without it
      seen: empty: JWT_SECRET
      fix:  give JWT_SECRET a value in the service's env file (or environment:), then start it again
```

When a stack worked yesterday and not today, `devdesk why` also lists what changed since the last clean start
(images, lock files, settings, code), ranked against what fails now. An image change can be **proven**: DevDesk
replays the old and the new image side by side in throwaway containers, with fresh empty copies of the databases they
need, and nothing of yours is touched.

## How it works

```mermaid
flowchart LR
    CLI["devdesk CLI"] --> Engine
    App["Desktop app<br/>(Tauri + Angular)"] -- "local API + token" --> Engine
    subgraph Engine["Engine (Java 25)"]
        Supervisor["Supervisor<br/>one writer per project"]
        Diagnosis["Diagnosis rules"]
        Recorder["Flight recorder<br/>exits, logs, changes"]
        Supervisor --> Recorder --> Diagnosis
    end
    Supervisor -- "own systemd scope per service" --> Native["Native processes"]
    Supervisor -- "owner label + project network" --> Containers["Containers<br/>(Docker / Podman)"]
    Diagnosis -- "why + evidence + fix" --> App
```

The engine is the only thing that starts or stops anything; the app and the CLI show what it reports. The core
(projects, dependency graph, state machine, health checks, diagnosis) has no Linux in it: Linux and Docker are
adapters behind interfaces, so the core is tested with fakes.

## Install

Runs on Linux. Tested on **Ubuntu 24.04** and on **Windows through WSL2** (Ubuntu). macOS is not supported.
Prebuilt installers (.deb, AppImage) will come with the first release; for now, build from source.

Needs git and JDK 25. Docker or Podman only if your projects use containers (the example project does). A systemd
user session (most Linux desktops; WSL2 with `systemd=true` in `/etc/wsl.conf`) gives the strongest cleanup guarantee.
On Ubuntu 24.04:

```bash
sudo apt install git openjdk-25-jdk python3          # python3 is only for the example project
git clone https://github.com/LouayChayeb/devdesk.git && cd devdesk
./gradlew :cli:installDist                            # about 5 minutes the first time (downloads Gradle and libraries)
export PATH="$PWD/cli/build/install/devdesk/bin:$PATH"
```

The desktop app also needs Node.js, Rust and the [Tauri prerequisites](https://v2.tauri.app/start/prerequisites/):

```bash
./gradlew :daemon:installDist
cd ui && npm install && npm run app
```

## Quick start

One example project to try everything: 3 containers (Postgres, Redis, Mailpit) and 8 Python services. Needs Docker
and python3.

```bash
cd examples/full-stack
devdesk why                 # checks ports, programs and settings first; runs nothing
devdesk trust               # shows every command it will run, asks once
devdesk up                  # starts in order, waits for health; Ctrl+C stops everything
```

[examples/full-stack/README.md](examples/full-stack/README.md) lists what to try in the app, and one-line ways to
break it on purpose (a taken port, a crash loop, an empty secret, an image update proven to break it) to see each
explanation.

Someone else's project, from its link:

```bash
devdesk get https://github.com/owner/project   # clone, propose a devdesk.yaml, trust, set up, start
devdesk remove ~/DevDesk/project               # later: delete everything DevDesk made for it, then check again
```

![The Get a project from a link dialog](.github/assets/get-dialog.png)

Other commands: `devdesk ps` (what runs, and leftovers), `devdesk history` (what happened while you weren't
looking), `devdesk doctor` (can this machine run it?), `devdesk once` (remember a one-time command like a README's
`rails db:setup`).

## How it compares

| | DevDesk | docker compose | Tilt | process-compose |
|---|---|---|---|---|
| Native processes and containers in one file | yes | containers | yes | processes |
| Dependency order with health checks | yes | yes | yes | yes |
| Explains a failure with evidence and a fix | yes | logs | logs and status | logs and status |
| "What changed since it last worked" | yes | no | no | no |
| Interface | desktop app + CLI | CLI | web UI + CLI | TUI + CLI |
| Platforms | Linux, WSL2 | Linux, macOS, Windows | Linux, macOS, Windows | Linux, macOS, Windows |
| Maturity | early, one developer | mature | mature | mature |

The others are mature and widely used; DevDesk is new. Its focus is answering "why is this broken?", not only
"what is running?".

## What was tested

- **15 real open-source projects**, including Mastodon, Chatwoot, Saleor, Twenty and Plausible, plus umami,
  spring-petclinic, paperless-ngx and the docker example voting app. Their problems shaped many of the rules.
- **120 hard kills, 0 leftovers**: on those 15 projects, DevDesk itself was killed 8 different ways (CLI and app,
  systemd scope and process group modes, while starting and while running), and every time the next start found and
  stopped what it had left.
- **About 890 automated tests** in CI on every push: unit tests with fakes, real Linux processes, real Docker, real
  databases, 15 crash points where DevDesk is killed on purpose, randomized ownership tests with bystander processes
  that must never get a signal, fuzzing and mutation testing.

## Status

Early. It has been used by its author, not yet by a team: expect bugs on other machines, and please
[open an issue](https://github.com/LouayChayeb/devdesk/issues) with what happened. Linux and WSL2 only; there are no
macOS or native Windows versions.

## Build

```bash
./gradlew build          # compile, test, check formatting and style
./gradlew spotlessApply  # auto-format the code
```

## License

[Apache-2.0](LICENSE)

## Credits

Built with the help of Claude Code.
