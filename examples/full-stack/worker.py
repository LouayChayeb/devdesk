# Background jobs: takes work from Redis and sends e-mail through mailpit: a confirmation (HTML, a link to the api,
# a receipt attached) for each new order, and a short note every 5th routine job.
import os, smtplib, socket, time
from email.message import EmailMessage
from pg import redis_cmd

redis = socket.create_connection(("127.0.0.1", int(os.environ["REDIS_PORT"])))
smtp_port = int(os.environ["SMTP_PORT"])
order_no = 0


def send_confirmation(order_json):
    """An HTML mail with a link back to the api and a receipt attached: the Mail page shows both."""
    global order_no
    order_no += 1
    msg = EmailMessage()
    msg["From"], msg["To"], msg["Subject"] = "Shop <shop@example.test>", "you@example.test", f"We got your order {order_no}"
    link = f"http://127.0.0.1:18080/orders/{order_no}"
    msg.set_content(f"Thanks! Your order: {order_json}\nTrack it: {link}")
    msg.add_alternative(f"<h2>Thanks for your order</h2><p><code>{order_json}</code></p>"
                        f"<p><a href=\"{link}\">Track your order</a></p>", subtype="html")
    msg.add_attachment(f"order {order_no}: {order_json}\n".encode(), maintype="text", subtype="plain", filename="receipt.txt")
    with smtplib.SMTP("127.0.0.1", smtp_port, timeout=5) as s:
        s.send_message(msg)
    print(f"worker: sent confirmation for order {order_no}", flush=True)


print("worker: connected to redis, waiting for jobs", flush=True)
job = 0
while True:
    # New orders first (POST /orders on the api queues them), else a routine job every 3 s.
    redis.sendall(b"*2\r\n$4\r\nLPOP\r\n$8\r\njobs:new\r\n")
    reply = redis.recv(65536).decode().split("\r\n")  # "$-1" when empty, else "$<length>", the order
    if reply[0] != "$-1":
        print(f"worker: picked up order {reply[1]}", flush=True)
        send_confirmation(reply[1])
        continue
    time.sleep(3)
    job += 1
    total = redis_cmd(redis, "INCR", "jobs")
    print(f"worker: job #{job} done (jobs so far: {total.lstrip(':')})", flush=True)
    if job % 5 == 0:
        msg = EmailMessage()
        msg["From"], msg["To"], msg["Subject"] = "shop@example.test", "you@example.test", f"Order #{job} shipped"
        msg.set_content(f"Job {job} finished at {time.strftime('%H:%M:%S')}.")
        with smtplib.SMTP("127.0.0.1", smtp_port, timeout=5) as s:
            s.send_message(msg)
        print(f"worker: sent mail for job #{job} (see http://127.0.0.1:58026)", flush=True)
