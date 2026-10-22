// The shop's API. /health checks that Postgres and Redis answer on the addresses it was given, so you can see
// DevDesk's wizard rewrite "db:5432" (a compose name) into "127.0.0.1:55450" when this runs on your machine.
const http = require('node:http');
const net = require('node:net');

const port = Number(process.env.PORT || 3100);
const database = new URL(process.env.DATABASE_URL);
const redis = new URL(process.env.REDIS_URL);

function reachable(host, port) {
  return new Promise((resolve) => {
    const socket = net.connect({ host, port: Number(port), timeout: 1000 }, () => { socket.end(); resolve(true); });
    socket.on('error', () => resolve(false));
    socket.on('timeout', () => { socket.destroy(); resolve(false); });
  });
}

http.createServer(async (req, res) => {
  if (req.url === '/health') {
    const db = await reachable(database.hostname, database.port);
    const cache = await reachable(redis.hostname, redis.port);
    res.writeHead(db && cache ? 200 : 503, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ database: `${database.hostname}:${database.port}`, db, cache: `${redis.hostname}:${redis.port}`, redis: cache }));
    return;
  }
  res.writeHead(200, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify({ products: ['mug', 'cap', 'sticker'] }));
}).listen(port, () => console.log(`api: listening on ${port} (database ${database.host}, redis ${redis.host})`));
