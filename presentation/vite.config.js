import { defineConfig } from 'vite'

// Dev default: fastLinkJS (faster recompilation).
// Production build: build-presentation.sh sets SCALA_JS_DEST to fullLinkJS output.
const scalaJsMain = process.env.SCALA_JS_DEST
  ?? '../out/presentation/fastLinkJS.dest/main.js'

export default defineConfig({
  server: {
    // Allow Vite dev server to serve files from repo root (where Mill's out/ lives)
    fs: { allow: ['..'] }
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
