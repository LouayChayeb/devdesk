# full-stack: try everything DevDesk does

A small but real-size project: 3 containers (Postgres, Redis, Mailpit) and 8 Python services (an API, a login
service, a worker, a scheduler, two web pages and a gateway), with a one-time database migration. It needs only
**Docker** and **python3** (no pip installs). Run every command from this folder.

```
gateway :18000 ──► web :18300, api :18080, auth :18081
api ──► postgres, redis        auth ──► postgres        admin ──► api
worker ──► redis, mailpit       scheduler ──► api (calls it every 5 s)
migrate runs once after postgres is ready; api and auth wait until it finished
```

## Run it

```bash
devdesk why        # checks ports, programs, Docker and settings first; starts nothing
devdesk trust      # shows every command it will run, asks once
devdesk up         # starts everything in order, waits for each health check; Ctrl+C stops it all
```

Then open http://127.0.0.1:18000 (the gateway) and http://127.0.0.1:58026 (Mailpit's inbox: the worker sends mail).
In another terminal: `devdesk ps` (what runs and who owns it) and `devdesk history` (what happened).

In the desktop app (open this folder), also try:

| Where | What to try |
|---|---|
| Services, Graph, Ports | the start order, the dependency graph, who listens where |
| Logs | `GET http://127.0.0.1:18080/orders/abc` crashes a request on purpose: find its traceback |
| Data | open postgres (the `orders` table) and redis (the `jobs:new` queue, the `orders` and `jobs` counters) |
| Inbox (turn **Extras** on in the menu, then restart the app) | the mails the worker sends, in Mailpit |
| API | import http://127.0.0.1:18080/api-json and send requests to api |
| Tasks (above the services) | **seed** (5 demo orders), **count-orders**, **reset-redis** (asks first) |
| Edit `api.py` and save | DevDesk restarts api by itself (`watch:` in devdesk.yaml) |

## Break it on purpose

Each one is a single command. Stop the project (Ctrl+C) between them.

| Try this | What you see |
|---|---|
| `python3 -m http.server 18000` in another terminal, then `devdesk why` | **Port conflict**: port 18000 is taken by that PID, which isn't part of the project, with `kill <pid>` to free it |
| `API_REDIS_PORT=56399 devdesk up` | **Crash loop**: api can't reach Redis on a port where nothing listens; DevDesk stops restarting it after 6 crashes, shows the error line, and says web, scheduler and gateway are blocked by api (root cause first) |
| `JWT_SECRET= devdesk up` | **Empty secret**: auth refuses to start, and DevDesk names the setting (`JWT_SECRET` is empty), never its value |
| In the app, pick the profile **broken-mail**, then Start | **Crash while running**: the worker starts fine, then crashes when it sends its first mail (wrong SMTP port) |

## An image update that breaks it ("it worked yesterday")

`devdesk.image-change.yaml` is a second, tiny project in this folder: a `greeter` container from a local image and a
`client` that waits for it. The image changes under the same tag, like a `docker pull` of `:latest`.

```bash
F=devdesk.image-change.yaml
docker build -t devdesk-example/greeter:latest image-change/v1   # greeter 1.0
devdesk trust -f $F && devdesk up -f $F                          # all healthy; Ctrl+C. DevDesk remembers this clean start
docker build -t devdesk-example/greeter:latest image-change/v2   # greeter 2.0, same tag
devdesk up -f $F                                                 # greeter crashes, client is blocked; Ctrl+C
devdesk why -f $F                                                # LIKELY: the image changed (old id → new id), ranked first
devdesk why -f $F --prove                                        # PROVEN: the old image passes the same health check, the new one fails
docker image rm devdesk-example/greeter:latest                   # clean up
```

The proof runs both images in throwaway containers on their own network and random ports; nothing of yours is
touched.

## Clean up

`devdesk remove` deletes everything DevDesk made for this project (containers, network, the volumes and images it
created, its saved history), after showing the list.
