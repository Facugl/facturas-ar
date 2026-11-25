import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { SignIn } from './SignIn'

/**
 * What the sign-in screen says when something goes wrong.
 *
 * <h2>Why this file exists</h2>
 *
 * <p>Because the first time anybody actually opened the screen, a wrong
 * password answered <strong>"No se pudo conectar con la API"</strong> —
 * and the API had answered perfectly, with a 401. The client turns a 401
 * into its own {@code Unauthorized} type before an {@code ApiError} is
 * ever constructed, so the {@code status === 401} check sitting inside
 * the {@code ApiError} branch could never run.
 *
 * <p>That is the worst shape an error message can have: it sends the
 * person to check their internet connection instead of their password.
 * And twenty-two frontend tests did not catch it, because none of them
 * covered this mapping — the components were tested for what they render
 * and the client for how it throws, and nobody tested the join.
 *
 * <p>So these are deliberately about <em>the words on the screen</em> and
 * not about the types. A test asserting "an Unauthorized maps to an
 * Unauthorized message" would re-encode the bug.
 */
describe('los errores de la pantalla de entrar', () => {
  let fetchMock: ReturnType<typeof vi.fn>

  function renderSignIn() {
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    return render(
      <QueryClientProvider client={client}>
        <SignIn />
      </QueryClientProvider>,
    )
  }

  /**
   * Fills the form and submits it, the way somebody would.
   *
   * The submit button is matched by a regex because its label changes
   * with the mode — "Entrar" when signing in, "Crear cuenta y entrar"
   * when registering — and only those two contain the word.
   */
  async function signIn(password = 'unaClaveLarga') {
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('Email'), 'yo@ejemplo.ar')
    await user.type(screen.getByLabelText('Contraseña'), password)
    await user.click(screen.getByRole('button', { name: /entrar/i }))
  }

  beforeEach(() => {
    fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    document.cookie = 'XSRF-TOKEN=un-token'
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  /** The bug, as the person experienced it. */
  it('una clave equivocada dice que la clave esta mal, no que la API no responde', async () => {
    fetchMock.mockImplementation(async () => new Response('', { status: 401 }))
    renderSignIn()

    await signIn('laClaveEquivocada')

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Email o contraseña incorrectos.',
    )
    expect(screen.getByRole('alert')).not.toHaveTextContent('conectar')
  })

  /**
   * One answer for "no such email" and "wrong password", on purpose:
   * telling them apart is the first half of a credential stuffing list.
   * The screen has to be as careful as the API is.
   */
  it('un email inexistente dice exactamente lo mismo que una clave mal', async () => {
    fetchMock.mockImplementation(async () => new Response('', { status: 401 }))
    renderSignIn()

    await signIn()

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Email o contraseña incorrectos.',
    )
  })

  it('un email ya registrado se explica, no se muestra el 409 crudo', async () => {
    const user = userEvent.setup()
    fetchMock.mockImplementation(async () =>
      new Response(JSON.stringify({ status: 409 }), {
        status: 409,
        headers: { 'Content-Type': 'application/json' },
      }),
    )
    renderSignIn()

    await user.click(screen.getByRole('button', { name: 'Crear una cuenta' }))
    await signIn()

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Ese email ya tiene una cuenta.',
    )
  })

  /**
   * The API builds a useful `detail` for a 400 — which field and why —
   * and that is the whole reason it bothers. Showing "Error 400" instead
   * would throw away the only actionable part.
   */
  it('un 400 muestra el detalle que la API se tomo el trabajo de armar', async () => {
    fetchMock.mockImplementation(async () =>
      new Response(
        JSON.stringify({ status: 400, detail: 'password: At least 8 characters' }),
        { status: 400, headers: { 'Content-Type': 'application/json' } },
      ),
    )
    renderSignIn()

    await signIn('corta')

    expect(await screen.findByRole('alert')).toHaveTextContent('At least 8 characters')
  })

  /**
   * And the message that was wrongly shown for everything is still right
   * for the thing it describes: the API genuinely not answering.
   */
  it('y si la API de verdad no responde, lo dice', async () => {
    fetchMock.mockImplementation(async () => {
      throw new TypeError('Failed to fetch')
    })
    renderSignIn()

    await signIn()

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'No se pudo conectar con la API.',
    )
  })
})
