import { useState } from 'react'

import { useIdentity, useSignOut } from './api/hooks'
import { SignIn } from './auth/SignIn'
import { History } from './history/History'
import { JobProgress } from './upload/JobProgress'
import { Upload } from './upload/Upload'
import { Spinner } from './ui/Bits'

/**
 * The shell: who is signed in, and which of the two screens is open.
 *
 * <h2>No router, and that is a decision with an expiry date</h2>
 *
 * Two tabs and a detail view. A router would buy deep links — being able
 * to reload on a comprobante, or send somebody a URL — and cost a
 * dependency and a layer of indirection over three pieces of state. At
 * this size the state wins. The moment there is a screen worth linking to
 * from outside the app, that flips, and the thing to add is
 * `react-router`.
 *
 * <h2>Not signed in is a state, not an error</h2>
 *
 * The app's first request is `/auth/me`. A 401 from it is the answer —
 * "nobody is signed in" — and it doubles as the handshake that makes the
 * server issue the CSRF cookie, which every write afterwards needs. See
 * `api/client.ts`.
 */
type Tab = 'upload' | 'history'

export function App() {
  const { data: identity, isLoading } = useIdentity()
  const signOut = useSignOut()

  const [tab, setTab] = useState<Tab>('upload')
  const [openJobId, setOpenJobId] = useState<string | null>(null)

  if (isLoading) {
    return (
      <div className="shell">
        <div className="card">
          <Spinner /> Conectando con la API…
        </div>
      </div>
    )
  }

  if (!identity) {
    return <SignIn />
  }

  return (
    <div className="shell">
      <header className="topbar">
        <div className="brand">
          facturas-ar
          <span>De la foto de un comprobante al CSV</span>
        </div>
        <div className="who">
          <span>{identity.email}</span>
          <button
            type="button"
            className="quiet"
            disabled={signOut.isPending}
            onClick={() => signOut.mutate()}
          >
            Salir
          </button>
        </div>
      </header>

      <nav className="tabs">
        <button
          type="button"
          className="tab"
          aria-selected={tab === 'upload' && openJobId === null}
          onClick={() => {
            setTab('upload')
            setOpenJobId(null)
          }}
        >
          Subir
        </button>
        <button
          type="button"
          className="tab"
          aria-selected={tab === 'history' || openJobId !== null}
          onClick={() => {
            setTab('history')
            setOpenJobId(null)
          }}
        >
          Historial
        </button>
      </nav>

      {openJobId !== null ? (
        <JobProgress jobId={openJobId} onDone={() => setOpenJobId(null)} />
      ) : tab === 'upload' ? (
        <Upload />
      ) : (
        <History onOpen={setOpenJobId} />
      )}
    </div>
  )
}
