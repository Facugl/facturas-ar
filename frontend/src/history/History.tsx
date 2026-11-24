import { useState } from 'react'

import { useExport, useHistory } from '../api/hooks'
import type { HistoryRow } from '../api/types'
import {
  Notice,
  Spinner,
  StatusBadge,
  formatAmount,
  formatDate,
  formatDateTime,
  today,
} from '../ui/Bits'

/**
 * Everything this user has loaded, and the export.
 *
 * <h2>Two date ranges on one screen, on purpose</h2>
 *
 * The list filters by <strong>upload</strong> date and the export by
 * <strong>issue</strong> date, and they are labelled so. That is not an
 * inconsistency to hide: a comprobante from March loaded in May belongs
 * to March in an accounting file and to May in "what did I do today".
 * Filtering the list by issue date would hide exactly what somebody opens
 * a history to find — the failures, and the ones whose QR never decoded
 * and so have no issue date at all.
 *
 * Giving them one shared range would mean one of the two questions gets
 * the wrong answer silently, which is worse than two labelled inputs.
 */
export function History({ onOpen }: { onOpen: (jobId: string) => void }) {
  const [page, setPage] = useState(0)
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')

  const { data, isLoading, error, isPlaceholderData } = useHistory({
    from: from || undefined,
    to: to || undefined,
    page,
  })

  const pages = data ? Math.ceil(data.total / Math.max(data.size, 1)) : 0

  return (
    <>
      <Export />

      <div className="card">
        <h2>Historial</h2>
        <p className="muted">
          Todo lo que subiste, lo más reciente primero. El filtro es por{' '}
          <strong>fecha de carga</strong> — el del export, de arriba, es por fecha de
          emisión.
        </p>

        <div className="row">
          <div>
            <label htmlFor="history-from">Cargado desde</label>
            <input
              id="history-from"
              type="date"
              value={from}
              max={to || undefined}
              onChange={(event) => {
                setFrom(event.target.value)
                setPage(0)
              }}
            />
          </div>
          <div>
            <label htmlFor="history-to">hasta</label>
            <input
              id="history-to"
              type="date"
              value={to}
              min={from || undefined}
              onChange={(event) => {
                setTo(event.target.value)
                setPage(0)
              }}
            />
          </div>
          {(from || to) && (
            <button
              type="button"
              className="quiet"
              style={{ flex: '0 0 auto' }}
              onClick={() => {
                setFrom('')
                setTo('')
                setPage(0)
              }}
            >
              Limpiar
            </button>
          )}
        </div>
      </div>

      {error && <Notice kind="error">No se pudo cargar el historial.</Notice>}

      {isLoading && (
        <div className="card">
          <Spinner /> Cargando…
        </div>
      )}

      {data && data.jobs.length === 0 && (
        <Notice kind="info">
          {from || to
            ? 'No cargaste nada en ese rango de fechas.'
            : 'Todavía no subiste ningún comprobante.'}
        </Notice>
      )}

      {data && data.jobs.length > 0 && (
        <div style={{ opacity: isPlaceholderData ? 0.6 : 1 }}>
          {data.jobs.map((row) => (
            <Row key={row.jobId} row={row} onOpen={onOpen} />
          ))}

          <div className="actions">
            <button type="button" disabled={page === 0} onClick={() => setPage(page - 1)}>
              Anteriores
            </button>
            <button
              type="button"
              disabled={page + 1 >= pages}
              onClick={() => setPage(page + 1)}
            >
              Siguientes
            </button>
            <span className="muted" style={{ alignSelf: 'center' }}>
              {data.jobs.length} de {data.total}
            </span>
          </div>
        </div>
      )}
    </>
  )
}

/**
 * One line.
 *
 * A button and not a div, because it is clickable: that is what gets it
 * keyboard focus and makes a screen reader announce it as something you
 * can activate, for free.
 */
function Row({ row, onOpen }: { row: HistoryRow; onOpen: (jobId: string) => void }) {
  return (
    <button type="button" className="history-row" onClick={() => onOpen(row.jobId)}>
      <div className="history-main">
        <div className="history-title">
          {row.issuerBusinessName ?? row.originalFilename}
        </div>
        <div className="muted">
          {/* The type and number when they are known, and the filename
              when they are not — which is the case this list has to
              handle, not an edge of it. */}
          {row.invoiceType ?? (row.invoiceTypeCode ? `Tipo ${row.invoiceTypeCode}` : null)}
          {row.issueDate ? ` · ${formatDate(row.issueDate)}` : ''}
          {!row.issueDate && ` · cargado ${formatDateTime(row.uploadedAt)}`}
          {row.qrDetected === false && ' · sin QR'}
        </div>
      </div>
      <div className="history-amount">
        {row.total === undefined ? '' : `$${formatAmount(row.total)}`}
      </div>
      <StatusBadge status={row.status} />
    </button>
  )
}

/**
 * The export, which is the product.
 *
 * Defaults to the current month because that is the period somebody
 * exports, and typing two dates to get it would be the first thing to
 * annoy them.
 */
function Export() {
  const now = today()
  const [from, setFrom] = useState(`${now.slice(0, 7)}-01`)
  const [to, setTo] = useState(now)

  const download = useExport()

  return (
    <div className="card">
      <h2>Exportar a CSV</h2>
      <p className="muted">
        Por <strong>fecha de emisión</strong>, y solo los confirmados: lo que no
        revisaste no entra en un archivo que va a un contador. Abre en Excel sin tocar
        nada.
      </p>

      {download.error && (
        <Notice kind="error">
          {download.error instanceof Error ? download.error.message : 'No se pudo exportar.'}
        </Notice>
      )}

      <div className="row">
        <div>
          <label htmlFor="export-from">Emitido desde</label>
          <input
            id="export-from"
            type="date"
            value={from}
            max={to}
            onChange={(event) => setFrom(event.target.value)}
          />
        </div>
        <div>
          <label htmlFor="export-to">hasta</label>
          <input
            id="export-to"
            type="date"
            value={to}
            min={from}
            onChange={(event) => setTo(event.target.value)}
          />
        </div>
        <button
          className="primary"
          type="button"
          style={{ flex: '0 0 auto' }}
          disabled={download.isPending || !from || !to}
          onClick={() => download.mutate({ from, to })}
        >
          {download.isPending ? 'Generando…' : 'Descargar CSV'}
        </button>
      </div>
      <div className="field-note">
        El rango no puede pasar de un año, y la fecha de inicio no puede ser posterior a
        la de fin.
      </div>
    </div>
  )
}
