# Calls the API every 5 seconds, like a cron job. Logs a warning when the API is slow or down.
import json, os, time, urllib.request

api = os.environ["API"]
while True:
    started = time.time()
    try:
        with urllib.request.urlopen(api + "/orders", timeout=2) as r:
            n = json.load(r)["orders"]
        print(f"scheduler: tick, api has {n} orders ({(time.time() - started) * 1000:.0f} ms)", flush=True)
    except Exception as e:
        print(f"scheduler: WARNING api did not answer: {e}", flush=True)
    time.sleep(5)
