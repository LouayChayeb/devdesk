// The shop's web page: shows the products it gets from the API.
const http = require('node:http');

const port = Number(process.env.PORT || 3200);
const api = process.env.API_URL || 'http://127.0.0.1:3100';

http.createServer(async (req, res) => {
  try {
    const products = (await (await fetch(api + '/')).json()).products;
    res.writeHead(200, { 'Content-Type': 'text/html' });
    res.end(`<h1>Shop</h1><ul>${products.map((p) => `<li>${p}</li>`).join('')}</ul>`);
  } catch (e) {
    res.writeHead(502, { 'Content-Type': 'text/plain' });
    res.end(`web: the API at ${api} did not answer (${e.cause?.code ?? e.message})`);
  }
}).listen(port, () => console.log(`web: listening on ${port}, API at ${api}`));
