# DevDesk

[![build](https://github.com/LouayChayeb/devdesk/actions/workflows/build.yml/badge.svg)](https://github.com/LouayChayeb/devdesk/actions/workflows/build.yml)

**The local-dev tool that cleans up what it started, never touches anything else, and tells you why your stack
broke, with evidence.**

Hybrid native + Docker on Linux and WSL2. Offline. No AI.

You describe your stack once in `devdesk.yaml` (`npm run dev`, `uvicorn ...`, Postgres in Docker, ...). DevDesk
starts it in dependency order, waits for real health checks, keeps the logs, and stops exactly what it started.
When something breaks, it explains why:

```
$ devdesk up            # examples/broken/wrong-port.yaml
▶ api running, PID 16241 (0.1s)
  ✗ api never became healthy
      why:  api started, but its health check never passed: GET http://127.0.0.1:3006/: connection refused
            (nothing listening yet?) (2 checks in a row). It listens on [3005], but the check waits on port 3006.
      seen: check failed: GET http://127.0.0.1:3006/: connection refused (nothing listening yet?)  (6 times)
      seen: api (PIDs [16241, 16247]) listens on ports [3005]
      fix:  make the health check use port 3005, or make api listen on 3006
```

And when it worked yesterday, it tells you what changed since, ranked against what fails now:

```
$ devdesk why --prove   # examples/image-change, after the image was upgraded
What changed since it last worked (Oct 21 21:38), most likely first:
  1. PROVEN   image devdesk-example/greeter:latest: now a different image (c869ef799685 → bdb318d7f9fb): it was
              pulled again or rebuilt since
       why:  greeter runs this image and crashes
       why:  Proven: the image greeter used before passes the same health check, the current
             devdesk-example/greeter:latest fails it.
       seen: before (c869ef799685): check passed: GET http://127.0.0.1:32775/ → HTTP 200
       seen: now (devdesk-example/greeter:latest): exited. Last output: greeter 2.0: GREETER_MODE is required
       note: Replayed without your volume data, on its own network, without the other services, on random ports; 4s.
```

Only an image change is ever called PROVEN. DevDesk replays the old and the new image in throwaway containers (own
network, random ports, no volumes, 30 s cap). Code changes get the exact `git bisect` commands instead. Lock files and
settings stay LIKELY or UNKNOWN, because DevDesk can't replay them safely.

## The guarantee, honestly

- **It only touches what it started.** Processes are owned by a systemd scope, or by a process group plus start time
  plus a random marker. Containers are owned by labels with the project's key. Nothing is ever killed by name or by
  port. A container with the name DevDesk wants but not its labels is left alone, and DevDesk says so.
- **With a systemd user session (most Linux desktops, WSL2 with `systemd=true`): zero leftovers.** Every service runs
  in its own scope, so stopping it stops every child and grandchild, even ones that changed their process group.
- **Without systemd (process-group mode): leftovers are detected and reported.** A service that escapes its process
  group can outlive a stop. If DevDesk itself is killed (`kill -9`, crash), the next start finds what it left, says so,
  and stops it.
- **Secrets stay put.** Env values are never logged or shown by default, and change tracking compares fingerprints
  only.
- **Nothing runs without your OK.** A project must be trusted (`devdesk trust`) before its commands run, and again
  each time `devdesk.yaml` changes.

These are tested, not just promised: hard-kill recovery, randomized ownership tests with bystander processes and
containers, and the local API's token, Host and Origin checks all run in CI.

## Quickstart

Needs Linux (or WSL2) and JDK 25. Docker or Podman only if your project has containers.

```bash
./gradlew :cli:installDist
export PATH="$PWD/cli/build/install/devdesk/bin:$PATH"

cd examples/three-tier      # db ← api ← web, needs only python3
devdesk why                 # checks ports, programs and settings first; runs nothing
devdesk trust               # shows the commands it will run, asks once
devdesk up                  # starts in order, waits for health; Ctrl+C stops everything
```

Other commands: `devdesk ps` (what runs, and leftovers), `devdesk history` (what happened while you weren't looking),
`devdesk doctor` (can this machine run it?), `devdesk why --prove` (replay a changed image).

`examples/image-change` breaks a stack with an image upgrade and lets you watch DevDesk prove it.

### The desktop app

```bash
./gradlew :daemon:installDist
cd ui && npm install && npm run app
```

Extras (mock server, traffic recorder, stress lab, Kafka / SQS / S3 / Elasticsearch / RabbitMQ pages, running on
another machine, auto-sleep) are off by default. Turn them on in the app's project menu, with `extras=on` in
`~/.config/devdesk/settings.properties`, or with `DEVDESK_EXTRAS=on`.

## Build

```bash
./gradlew build          # compile, test, check formatting and style
./gradlew spotlessApply  # auto-format the code
```

## License

[Apache-2.0](LICENSE)
