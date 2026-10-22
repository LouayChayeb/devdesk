# Task "seed": adds 5 demo orders. -q keeps psql from printing "INSERT 0 5", which would look like ids. Run it from the app's task buttons (it needs migrate, so the tables exist).
import subprocess, sys

sql = """
insert into orders(user_id, status, total)
    select 1 + (n % 40), 'new', round((10 + random() * 90)::numeric, 2) from generate_series(1, 5) n
returning id;
"""
r = subprocess.run(["docker", "exec", "-i", "devdesk-full-stack-postgres", "psql", "-U", "postgres", "-d", "shop",
                    "-qtA", "-v", "ON_ERROR_STOP=1"], input=sql, text=True, capture_output=True)
if r.returncode != 0:
    print("seed: failed:", r.stderr.strip(), flush=True)
    sys.exit(1)
ids = [line for line in r.stdout.split() if line.isdigit()]
print(f"seed: added {len(ids)} orders (ids {', '.join(ids)})", flush=True)
