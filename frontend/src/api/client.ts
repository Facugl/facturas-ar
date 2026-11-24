/**
 * Every request the app makes goes through here.
 *
 * <h2>The CSRF dance, in one place</h2>
 *
 * The API keeps the session in an `HttpOnly` cookie, so the browser
 * attaches it by itself — including to a request from somebody else's
 * page. The defence is a token the server puts in a cookie the page *can*
 * read (`XSRF-TOKEN`) and expects echoed back in a header
 * (`X-XSRF-TOKEN`). A write without the echo is not one our page made.
 *
 * That means two things no component should have to know:
 *
 * 1. **A write needs the cookie to already exist.** The server only
 *    issues it on a request that passes through its CSRF filter, so the
 *    app does a plain `GET` first. `ensureCsrfToken` is that, and it is
 *    why the app's first call on load is `/auth/me` — it doubles as the
 *    "am I signed in" question and as the handshake.
 * 2. **The token has to be read fresh.** Logging out invalidates the
 *    session and the server may hand out a different token afterwards, so
 *    reading the cookie once at module load and keeping it would send a
 *    stale one after the next sign-in.
 */

const CSRF_COOKIE = 'XSRF-TOKEN'
const CSRF_HEADER = 'X-XSRF-TOKEN'

/** A request the server refused for lack of a session. */
export class Unauthorized extends Error {
  constructor() {
    super('No hay sesión')
    this.name = 'Unauthorized'
  }
}

/**
 * An error the API described in a `ProblemDetail` body.
 *
 * Carries `detail` because the API puts something useful there — which
 * field of the review form was wrong and why — and showing "Error 400"
 * instead would throw that away.
 */
export class ApiError extends Error {
  readonly status: number

  constructor(status: number, detail: string) {
    super(detail)
    this.name = 'ApiError'
    this.status = status
  }
}

function readCookie(name: string): string | undefined {
  return document.cookie
    .split('; ')
    .find((entry) => entry.startsWith(`${name}=`))
    ?.slice(name.length + 1)
}

/**
 * Makes sure the CSRF cookie exists, with a GET if it does not.
 *
 * `/actuator/health` is the request used for it: it is public, cheap, and
 * answers before anybody is signed in — which is precisely when the token
 * is needed, since registering and logging in are both writes.
 */
async function ensureCsrfToken(): Promise<string | undefined> {
  const existing = readCookie(CSRF_COOKIE)
  if (existing) {
    return existing
  }
  await fetch('/actuator/health', { credentials: 'same-origin' })
  return readCookie(CSRF_COOKIE)
}

type RequestOptions = {
  method?: string
  /** Sent as JSON. Use `formData` for an upload instead. */
  body?: unknown
  formData?: FormData
  /** Treat a 404 as "nothing there" and answer null rather than throwing. */
  nullOn404?: boolean
}

async function request(path: string, options: RequestOptions = {}): Promise<Response> {
  const method = options.method ?? 'GET'
  const headers: Record<string, string> = {}

  if (method !== 'GET' && method !== 'HEAD') {
    const token = await ensureCsrfToken()
    if (token) {
      headers[CSRF_HEADER] = token
    }
  }

  let body: BodyInit | undefined
  if (options.formData) {
    // No Content-Type on purpose: the browser has to set it, because only
    // the browser knows the multipart boundary it generated.
    body = options.formData
  } else if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json'
    body = JSON.stringify(options.body)
  }

  const response = await fetch(path, {
    method,
    headers,
    body,
    // The session cookie travels on every request. Same-origin because
    // the dev server and the deployed nginx both proxy the API under this
    // origin — see vite.config.ts for why that is not incidental.
    credentials: 'same-origin',
  })

  if (response.status === 401) {
    throw new Unauthorized()
  }
  if (!response.ok) {
    throw new ApiError(response.status, await detailOf(response))
  }
  return response
}

/**
 * Pulls the message out of an RFC 7807 body, falling back to the status.
 *
 * Every error the API raises on purpose has a `detail` worth reading; a
 * proxy error or a dropped connection has nothing, and guessing is worse
 * than saying the status.
 */
async function detailOf(response: Response): Promise<string> {
  try {
    const problem = await response.json()
    if (typeof problem?.detail === 'string') {
      return problem.detail
    }
  } catch {
    // Not JSON, which is itself the answer: fall through.
  }
  return `La API respondió ${response.status}`
}

export async function get<T>(path: string, nullOn404 = false): Promise<T | null> {
  try {
    const response = await request(path)
    return (await response.json()) as T
  } catch (error) {
    if (nullOn404 && error instanceof ApiError && error.status === 404) {
      return null
    }
    throw error
  }
}

export async function post<T>(path: string, body?: unknown): Promise<T | null> {
  const response = await request(path, { method: 'POST', body })
  return await jsonOrNull<T>(response)
}

export async function put<T>(path: string, body?: unknown): Promise<T | null> {
  const response = await request(path, { method: 'PUT', body })
  return await jsonOrNull<T>(response)
}

export async function upload<T>(path: string, file: File): Promise<T | null> {
  const formData = new FormData()
  formData.append('file', file)
  const response = await request(path, { method: 'POST', formData })
  return await jsonOrNull<T>(response)
}

/**
 * Downloads a file and hands the browser the filename the server chose.
 *
 * The export's `Content-Disposition` already names the file
 * (`comprobantes-2026-03-01-a-2026-03-31.csv`), and that name is part of
 * the feature: a folder of files called `download.csv` is not a history.
 */
export async function download(path: string): Promise<void> {
  const response = await request(path)
  const blob = await response.blob()

  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filenameFrom(response) ?? 'comprobantes.csv'
  link.click()
  URL.revokeObjectURL(url)
}

function filenameFrom(response: Response): string | undefined {
  const disposition = response.headers.get('Content-Disposition')
  return disposition?.match(/filename="?([^"]+)"?/)?.[1]
}

/** 204 has no body, and `response.json()` on one throws. */
async function jsonOrNull<T>(response: Response): Promise<T | null> {
  if (response.status === 204) {
    return null
  }
  const text = await response.text()
  return text ? (JSON.parse(text) as T) : null
}
