import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

/**
 * The paths the API owns. Everything else is the SPA.
 *
 * Spelled out rather than mounting the API under a prefix like `/api`,
 * because the API's paths are already documented, exercised by curl in
 * the README and asserted by the tests. Moving them to please the
 * frontend would be the frontend deciding the API's contract.
 */
const apiPaths = ['/auth', '/documents', '/jobs', '/export', '/actuator']

/**
 * The dev server proxies the API instead of the app talking to it
 * across origins, and that is the one frontend decision with real
 * consequences.
 *
 * The session lives in a cookie marked `HttpOnly` and `SameSite=Lax`
 * (ADR 015). Cross-origin, a browser will not send it: `Lax` is exactly
 * the rule that stops that, and the only way around it is
 * `SameSite=None; Secure`, which needs HTTPS and so does not work on
 * `http://localhost` at all. The alternatives were to weaken the cookie,
 * or to drop cookies for a token and undo ADR 015 to suit the dev
 * server. Proxying costs one config block and keeps the browser seeing
 * one origin, which is also what the deployed setup does — so what is
 * tested in development is the thing that ships.
 */
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: Object.fromEntries(
      apiPaths.map((path) => [
        path,
        { target: 'http://localhost:8080', changeOrigin: false },
      ]),
    ),
  },
})
