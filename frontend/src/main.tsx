import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'

import { App } from './App'
import { Unauthorized } from './api/client'
import './index.css'

/**
 * Two defaults worth spelling out.
 *
 * `retry` never retries a 401: a request refused for lack of a session
 * will be refused three more times, and all that buys is making "you are
 * not signed in" take a second and a half to say. Everything else gets
 * one retry, which covers a dropped connection without turning a real
 * error into a four-second wait.
 *
 * `refetchOnWindowFocus` is off. The default is reasonable for a
 * dashboard; here it would re-fetch a job the moment somebody tabs back,
 * and the one screen where freshness matters already polls on purpose.
 */
const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: (attempt, error) => !(error instanceof Unauthorized) && attempt < 1,
      refetchOnWindowFocus: false,
    },
  },
})

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <App />
    </QueryClientProvider>
  </StrictMode>,
)
