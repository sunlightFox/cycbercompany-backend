const http = require('node:http')
const fs = require('node:fs')
const net = require('node:net')
const path = require('node:path')

const root = path.join(__dirname, 'dist')
const upstream = { hostname: '127.0.0.1', port: 8080 }
const types = {
  '.css': 'text/css; charset=utf-8',
  '.html': 'text/html; charset=utf-8',
  '.ico': 'image/x-icon',
  '.js': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.png': 'image/png',
  '.svg': 'image/svg+xml',
  '.woff2': 'font/woff2',
}

function proxy(request, response) {
  const forwardedHeaders = {
    ...request.headers,
    host: '127.0.0.1:8080',
    // The backend uses this only in temporary LOCAL demo mode. Do not trust a
    // client-supplied value: overwrite it with the proxy's socket address.
    'x-forwarded-for': request.socket.remoteAddress ?? '',
  }
  const upstreamRequest = http.request({
    ...upstream,
    method: request.method,
    path: request.url,
    headers: forwardedHeaders,
  }, (upstreamResponse) => {
    response.writeHead(upstreamResponse.statusCode ?? 502, upstreamResponse.headers)
    upstreamResponse.pipe(response)
  })
  upstreamRequest.on('error', (error) => {
    response.writeHead(502, { 'Content-Type': 'application/json' })
    response.end(JSON.stringify({ message: `Backend proxy error: ${error.message}` }))
  })
  request.pipe(upstreamRequest)
}

const server = http.createServer((request, response) => {
  if (request.url?.startsWith('/api/')) return proxy(request, response)

  const pathname = decodeURIComponent(new URL(request.url ?? '/', 'http://localhost').pathname)
  const candidate = path.resolve(root, `.${pathname}`)
  if (!candidate.startsWith(root)) {
    response.writeHead(403).end()
    return
  }
  const file = fs.existsSync(candidate) && fs.statSync(candidate).isFile()
    ? candidate
    : path.join(root, 'index.html')
  response.writeHead(200, { 'Content-Type': types[path.extname(file)] ?? 'application/octet-stream' })
  fs.createReadStream(file).pipe(response)
})

server.on('upgrade', (request, clientSocket, head) => {
  if (!request.url?.startsWith('/api/')) return clientSocket.destroy()
  const upstreamSocket = net.connect(upstream.port, upstream.hostname, () => {
    const headers = Object.entries(request.headers)
      .map(([name, value]) => `${name}: ${value}`)
      .join('\r\n')
    upstreamSocket.write(`${request.method} ${request.url} HTTP/${request.httpVersion}\r\n${headers}\r\n\r\n`)
    if (head.length) upstreamSocket.write(head)
    clientSocket.pipe(upstreamSocket).pipe(clientSocket)
  })
  upstreamSocket.on('error', () => clientSocket.destroy())
})

server.listen(5173, '0.0.0.0')
