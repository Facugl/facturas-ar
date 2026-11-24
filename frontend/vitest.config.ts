import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

/**
 * Separate from `vite.config.ts` so the dev-server proxy and the test
 * environment do not have to share a file. Nothing here is needed to
 * build the app, and nothing there is needed to test it.
 *
 * <h2>Why vitest 3 and not 4</h2>
 *
 * 4.1.12 does not run here at all: a file containing one
 * `expect(1 + 1).toBe(2)` fails to collect with `Cannot read properties
 * of undefined (reading 'config')` — with no config file, no setup file,
 * a freshly installed `node_modules`, and on either pool. Tried,
 * measured, reverted, rather than left on the newest version because it
 * is the newest.
 *
 * The cost of staying is written down instead of hidden: `@vitest/mocker`
 * carries two advisories with no fix in the 3.x line, and `npm audit`
 * says so on every install. They are reachable through the mocker's HTTP
 * endpoint, which belongs to vitest's browser mode and UI — neither of
 * which this project uses — and this is a dev dependency that never
 * reaches an image. `tinypool`, the one that was genuinely critical, is
 * pinned to its patched 2.1.2 through an `overrides` entry in
 * package.json.
 */
export default defineConfig({
  plugins: [react()],
  test: {
    // jsdom, because what is under test is a form and a fetch wrapper:
    // both are DOM-shaped, and asserting on them without a DOM would
    // mean asserting on the implementation instead.
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
  },
})
