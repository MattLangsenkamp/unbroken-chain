import { defineConfig } from 'vite'
import path from 'path'
import { fileURLToPath } from 'url'
import { existsSync } from 'fs'

const __dirname = path.dirname(fileURLToPath(import.meta.url))

// Walk up from this directory until we find build.mill (the repo root)
function findRepoRoot(dir) {
  if (existsSync(path.join(dir, 'build.mill'))) return dir
  const parent = path.dirname(dir)
  if (parent === dir) throw new Error('Could not find repo root (no build.mill found)')
  return findRepoRoot(parent)
}

const repoRoot = findRepoRoot(__dirname)
// e.g. "provider-gateways/github-gateway/presentation" — mirrors Mill's module path and out/ structure
const millModulePath = path.relative(repoRoot, __dirname)
const millOutBase = path.join(repoRoot, 'out', millModulePath)

// Build script sets SCALA_JS_DEST (absolute path) to fullLinkJS for production.
// Dev default: fastLinkJS for faster recompilation.
const scalaJsMainAbs = process.env.SCALA_JS_DEST
  ?? path.join(millOutBase, 'fastLinkJS.dest', 'main.js')

// Relative to this directory (vite root) for use in the HTML src attribute
const scalaJsMain = path.relative(__dirname, scalaJsMainAbs)

export default defineConfig({
  server: {
    // Allow Vite dev server to serve files from the repo root (where Mill's out/ lives)
    fs: { allow: [repoRoot] }
  },
  plugins: [
    {
      name: 'scala-js-main',
      // Runs in both dev (on each request) and build (during bundle)
      transformIndexHtml(html) {
        return html.replace('__SCALA_JS_MAIN__', scalaJsMain)
      }
    }
  ]
})
