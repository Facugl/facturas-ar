import { useState } from 'react'

import { ApiError, Unauthorized } from '../api/client'
import { useRegister, useSignIn } from '../api/hooks'
import { Notice } from '../ui/Bits'

/**
 * Registering and signing in, on one screen with a toggle.
 *
 * <p>Two round trips on registering, not one: the API creates the account
 * and does <em>not</em> sign you in, so this calls register and then
 * login. That is the API's contract and the simpler one — each endpoint
 * does one thing — and the cost is paid here, once.
 */
export function SignIn() {
  const [isNew, setIsNew] = useState(false)
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')

  const register = useRegister()
  const signIn = useSignIn()

  const busy = register.isPending || signIn.isPending

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    register.reset()
    signIn.reset()

    if (isNew) {
      try {
        await register.mutateAsync({ email, password })
      } catch {
        // The mutation holds the error and it is rendered below. Stopping
        // here matters: signing in after a failed registration would
        // produce a second, more confusing error.
        return
      }
    }
    await signIn.mutateAsync({ email, password }).catch(() => undefined)
  }

  return (
    <div className="signin">
      <div className="brand" style={{ marginBottom: 20 }}>
        facturas-ar
        <span>De la foto de un comprobante al CSV que abre en Excel</span>
      </div>

      <form className="card" onSubmit={submit}>
        <h1>{isNew ? 'Crear una cuenta' : 'Entrar'}</h1>

        {register.error && <Notice kind="error">{messageFor(register.error)}</Notice>}
        {signIn.error && <Notice kind="error">{messageFor(signIn.error)}</Notice>}

        <div className="field">
          <label htmlFor="email">Email</label>
          <input
            id="email"
            type="email"
            autoComplete="username"
            required
            value={email}
            onChange={(event) => setEmail(event.target.value)}
          />
        </div>

        <div className="field">
          <label htmlFor="password">Contraseña</label>
          <input
            id="password"
            type="password"
            /* Tells the password manager which of the two this is, so it
               offers to save a new one instead of autofilling an old. */
            autoComplete={isNew ? 'new-password' : 'current-password'}
            required
            minLength={isNew ? 8 : undefined}
            value={password}
            onChange={(event) => setPassword(event.target.value)}
          />
          {isNew && <div className="field-note">Ocho caracteres o más.</div>}
        </div>

        <div className="actions">
          <button className="primary" type="submit" disabled={busy}>
            {busy ? 'Un momento…' : isNew ? 'Crear cuenta y entrar' : 'Entrar'}
          </button>
          <button
            className="quiet"
            type="button"
            onClick={() => {
              setIsNew(!isNew)
              register.reset()
              signIn.reset()
            }}
          >
            {isNew ? 'Ya tengo cuenta' : 'Crear una cuenta'}
          </button>
        </div>
      </form>
    </div>
  )
}

/**
 * Turns the API's answer into something worth reading.
 *
 * The two statuses that need translating are the ones where the API is
 * being deliberately terse: 409 says only that the insert hit the unique
 * index, and 401 is one answer for "no such email" and "wrong password"
 * on purpose — telling them apart is the first half of a credential
 * stuffing list. The screen has to be just as careful.
 */
function messageFor(error: unknown): string {
  // `Unauthorized` and not `ApiError`, and that distinction was a bug.
  //
  // The client turns a 401 into its own type before an `ApiError` is ever
  // constructed, so a `status === 401` check inside the `ApiError` branch
  // — which is what this had — can never run. A wrong password fell
  // through to the bottom and told the person **the API was
  // unreachable**, which sends them to check their internet connection
  // instead of their password.
  //
  // Found the first time anybody opened the screen. Twenty-two frontend
  // tests and none of them covered this mapping; `SignIn.test.tsx` now
  // does.
  if (error instanceof Unauthorized) {
    return 'Email o contraseña incorrectos.'
  }
  if (error instanceof ApiError) {
    if (error.status === 409) {
      return 'Ese email ya tiene una cuenta. Probá entrar.'
    }
    return error.message
  }
  return 'No se pudo conectar con la API.'
}
