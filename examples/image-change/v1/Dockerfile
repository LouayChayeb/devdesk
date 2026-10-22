# greeter 1.0: answers HTTP 200 on port 8080 (busybox nc, so it builds offline from alpine).
# The trap + wait lets it stop at once on SIGTERM (as PID 1, sh would otherwise ignore it).
FROM alpine:3
CMD ["sh", "-c", "trap 'exit 0' TERM; while true; do printf 'HTTP/1.1 200 OK\\r\\nContent-Length: 6\\r\\n\\r\\nhello\\n' | nc -l -p 8080 >/dev/null & wait $!; done"]
