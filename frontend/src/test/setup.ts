// `toBeInTheDocument`, `toBeDisabled` and the rest of the DOM matchers.
// The /vitest entry point is what registers them with expect().
import '@testing-library/jest-dom/vitest'

// Nothing unmounts components here on purpose: with `globals: true`,
// @testing-library/react registers its own cleanup against the global
// afterEach. Declaring a second one in a setup file is what Vitest 4
// refuses, because a setup file runs outside any suite.
