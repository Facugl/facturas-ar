import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError, Unauthorized, get, post } from './client'

/**
 * The fetch wrapper, and specifically the CSRF handshake.
 *
 * <p>This is worth testing and the components are not, because the
 * handshake is invisible when it breaks: every write answers 403, or
 * — on a {@code permitAll} endpoint with an anonymous caller — 401, and
 * nothing in the app says why. That exact confusion cost a session on the
 * API side already (ADR 015), and it was a missing cookie both times.
 */
describe('el cliente de la API', () => {
  let fetchMock: ReturnType<typeof vi.fn>

  function jsonResponse(body: unknown, status = 200) {
    return new Response(JSON.stringify(body), {
      status,
      headers: { 'Content-Type': 'application/json' },
    })
  }

  beforeEach(() => {
    fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    document.cookie = 'XSRF-TOKEN=; max-age=0'
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  /** What the request carried, by call index. */
  function callAt(index: number) {
    const [url, options] = fetchMock.mock.calls[index] as [string, RequestInit]
    return { url, options, headers: (options?.headers ?? {}) as Record<string, string> }
  }

  it('un GET no manda token de CSRF: no hace falta y pedirlo seria un request de mas', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse({ status: 'UP' }))

    await get('/actuator/health')

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(callAt(0).headers['X-XSRF-TOKEN']).toBeUndefined()
  })

  /**
   * The bootstrapping problem, and the reason this file exists. A write
   * is the first thing an anonymous visitor does — registering, or
   * logging in — and the token cookie does not exist yet, because the
   * server only hands it out on a request that passes its CSRF filter.
   */
  it('un POST sin cookie hace primero un GET para que el servidor la emita', async () => {
    fetchMock
      .mockImplementationOnce(async () => {
        // What the server does on that GET.
        document.cookie = 'XSRF-TOKEN=un-token-del-servidor'
        return new Response('', { status: 200 })
      })
      .mockResolvedValueOnce(jsonResponse({ id: 'u1', email: 'yo@test.ar' }))

    await post('/auth/login', { email: 'yo@test.ar', password: 'unaClaveLarga' })

    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(callAt(0).url).toBe('/actuator/health')
    expect(callAt(1).headers['X-XSRF-TOKEN']).toBe('un-token-del-servidor')
  })

  it('con la cookie ya puesta, el POST no hace el GET de mas', async () => {
    document.cookie = 'XSRF-TOKEN=ya-la-tengo'
    fetchMock.mockResolvedValueOnce(jsonResponse({ id: 'u1' }))

    await post('/auth/login', {})

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(callAt(0).headers['X-XSRF-TOKEN']).toBe('ya-la-tengo')
  })

  /**
   * Read fresh every time. Logging out invalidates the session and the
   * server may issue a different token afterwards; a value captured once
   * at module load would be sent stale after the next sign-in, and the
   * symptom would be that the first write after re-entering fails.
   */
  it('el token se lee en cada request, no se cachea', async () => {
    document.cookie = 'XSRF-TOKEN=el-primero'
    // A factory and not a single Response: a body can only be read once,
    // so handing the same object to two calls fails on the second with
    // "Body has already been read".
    fetchMock.mockImplementation(async () => jsonResponse({}))

    await post('/auth/logout')
    document.cookie = 'XSRF-TOKEN=el-segundo'
    await post('/documents')

    expect(callAt(0).headers['X-XSRF-TOKEN']).toBe('el-primero')
    expect(callAt(1).headers['X-XSRF-TOKEN']).toBe('el-segundo')
  })

  it('la cookie viaja en todos los requests, que es lo que sostiene la sesion', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse({}))

    await get('/auth/me')

    expect(callAt(0).options.credentials).toBe('same-origin')
  })

  // ── Errores ─────────────────────────────────────────────────────────

  it('un 401 es su propio tipo, porque no es un error sino una respuesta', async () => {
    fetchMock.mockResolvedValueOnce(new Response('', { status: 401 }))

    await expect(get('/auth/me')).rejects.toBeInstanceOf(Unauthorized)
  })

  /**
   * The API builds a useful `detail` on purpose — which field of the
   * review form was wrong and why — and showing "Error 400" would throw
   * away the one part the person can act on.
   */
  it('el detail del ProblemDetail se conserva: es lo unico accionable del error', async () => {
    // The cookie is pre-set so this is one request and not two: without
    // it the write would spend the mock on the CSRF handshake GET.
    document.cookie = 'XSRF-TOKEN=t'
    fetchMock.mockResolvedValueOnce(
      jsonResponse(
        { status: 400, detail: 'issuerCuit: not a valid CUIT — check the verifier digit' },
        400,
      ),
    )

    await expect(post('/jobs/x/confirm', {})).rejects.toThrow('not a valid CUIT')
  })

  it('un error que no es JSON no revienta el parseo, dice el status', async () => {
    fetchMock.mockResolvedValueOnce(new Response('<html>502</html>', { status: 502 }))

    await expect(get('/jobs')).rejects.toThrow('502')
  })

  /**
   * A job that is not there is a legitimate answer for the detail screen,
   * so it is asked for as one rather than thrown — but only where the
   * caller opted in, because a 404 on a list endpoint is a bug.
   */
  it('un 404 es null solo si quien llama lo pidio', async () => {
    fetchMock.mockImplementation(async () => jsonResponse({ status: 404 }, 404))

    await expect(get('/jobs/desconocido', true)).resolves.toBeNull()
    await expect(get('/jobs/desconocido')).rejects.toBeInstanceOf(ApiError)
  })

  /**
   * No `Content-Type` on an upload, and that is not an omission: only the
   * browser knows the multipart boundary it generated, so setting the
   * header by hand produces a body the server cannot parse.
   */
  it('el POST con JSON declara Content-Type, y el de un archivo no', async () => {
    document.cookie = 'XSRF-TOKEN=t'
    fetchMock.mockImplementation(async () => jsonResponse({}))

    await post('/auth/login', { email: 'a@b.ar' })
    expect(callAt(0).headers['Content-Type']).toBe('application/json')

    const { upload } = await import('./client')
    await upload('/documents', new File(['x'], 'f.pdf', { type: 'application/pdf' }))
    expect(callAt(1).headers['Content-Type']).toBeUndefined()
    expect(callAt(1).options.body).toBeInstanceOf(FormData)
  })

  it('un 204 no intenta parsear un cuerpo que no existe', async () => {
    document.cookie = 'XSRF-TOKEN=t'
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 204 }))

    await expect(post('/auth/logout')).resolves.toBeNull()
  })
})
