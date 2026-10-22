# Runs once: creates the schema and some demo data, then exits 0 so api and auth may start
# (condition: completed_successfully). Uses psql inside the Postgres container, so no Python driver is needed.
import os, subprocess, sys
from pg import pg_ping

port = int(os.environ["PG_PORT"])
if not pg_ping(port):
    print("migrate: postgres is not answering on", port, flush=True)
    sys.exit(1)

STEPS = [
    ("001_create_users", """
        create table if not exists users (
            id serial primary key, email text not null unique, name text, created_at timestamptz not null default now());
    """),
    ("002_create_products", """
        create table if not exists products (
            id serial primary key, sku text not null unique, title text not null, price numeric(10, 2) not null,
            stock int not null default 0);
    """),
    ("003_create_orders", """
        create table if not exists orders (
            id serial primary key, user_id int not null references users(id), status text not null default 'new',
            total numeric(10, 2) not null default 0, created_at timestamptz not null default now());
        create index if not exists orders_status on orders(status);
        create table if not exists order_items (
            order_id int not null references orders(id), product_id int not null references products(id),
            quantity int not null, primary key (order_id, product_id));
    """),
    ("004_demo_data", """
        insert into users(email, name)
            select 'user' || n || '@shop.test', (array['Ada','Bob','Chen','Dana','Eli','Fatma','Gus','Hana'])[1 + n % 8]
            from generate_series(1, 40) n on conflict do nothing;
        insert into products(sku, title, price, stock)
            select 'SKU-' || lpad(n::text, 4, '0'), (array['Mug','T-shirt','Sticker','Hoodie','Cap'])[1 + n % 5] || ' #' || n,
                   round((5 + random() * 60)::numeric, 2), (random() * 100)::int
            from generate_series(1, 25) n on conflict do nothing;
        insert into orders(user_id, status, total, created_at)
            select 1 + (n % 40), (array['new','paid','shipped','failed'])[1 + n % 4], round((10 + random() * 200)::numeric, 2),
                   now() - (n || ' hours')::interval
            from generate_series(1, 300) n where not exists (select 1 from orders);
        insert into order_items(order_id, product_id, quantity)
            select o.id, 1 + (o.id * 7) % 25, 1 + o.id % 3 from orders o on conflict do nothing;
    """),
]
for name, sql in STEPS:
    print(f"migrate: applying {name}.sql", flush=True)
    r = subprocess.run(["docker", "exec", "-i", "devdesk-full-stack-postgres", "psql", "-q", "-v", "ON_ERROR_STOP=1",
                        "-U", "postgres", "-d", "shop"], input=sql, text=True, capture_output=True)
    if r.returncode != 0:
        print(f"migrate: {name} failed:\n{r.stderr}", flush=True)
        sys.exit(1)
print("migrate: database is up to date", flush=True)
