import { readFileSync, readdirSync } from 'node:fs'
import { resolve } from 'node:path'
import react from '@vitejs/plugin-react'
import { defineConfig, type Plugin } from 'vite'

const SAMPLE_FILES = ['orders.csv', 'razorpay_settlement.csv', 'bank_statement.csv', 'answer_key.json']

/**
 * Serves the committed batch in `data/` at /sample/* so the Upload screen can offer it without the
 * files being duplicated into the frontend. Falls back to /data when running in the container.
 */
function sampleData(): Plugin {
  const roots = [resolve(__dirname, '../data'), '/data']
  const read = (name: string) => {
    for (const root of roots) {
      try {
        if (readdirSync(root).includes(name)) return readFileSync(resolve(root, name))
      } catch {
        // try the next root
      }
    }
    return null
  }
  return {
    name: 'ledgerlens-sample-data',
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        const name = req.url?.replace(/^\/sample\//, '').split('?')[0]
        if (!req.url?.startsWith('/sample/') || !name || !SAMPLE_FILES.includes(name)) return next()
        const body = read(name)
        if (!body) {
          res.statusCode = 404
          return res.end('sample data not found; run the generate profile first')
        }
        res.setHeader('Content-Type', name.endsWith('.json') ? 'application/json' : 'text/csv')
        res.end(body)
      })
    },
    generateBundle() {
      for (const name of SAMPLE_FILES) {
        const source = read(name)
        if (source) this.emitFile({ type: 'asset', fileName: `sample/${name}`, source })
      }
    },
  }
}

export default defineConfig({
  plugins: [react(), sampleData()],
  server: {
    port: 5173,
    proxy: { '/api': { target: process.env.API_URL ?? 'http://localhost:8080', changeOrigin: true } },
  },
})
