import { useMemo, useState } from 'react'

import { ApiError } from '../api/client'
import { useConfirm } from '../api/hooks'
import type { Corrections, FieldSource, Job } from '../api/types'
import { Notice, SourceBadge } from '../ui/Bits'

/**
 * The review form: the screen the whole project points at.
 *
 * <h2>Every field shows where it came from</h2>
 *
 * That badge is not decoration. `QR` means ARCA signed the value and it
 * is exact; `OCR` means a machine guessed at a photograph. A form that
 * presented both as "the data" would be hiding the single most useful
 * thing it knows, and the person would have to check all nine fields or
 * none.
 *
 * <h2>QR fields are read-only until you ask</h2>
 *
 * A value from the QR is right, so the default is to not invite editing
 * it — a stray keystroke in a CAE is a worse outcome than a click.
 * "Editar" unlocks it, because the API accepts the correction either way
 * and criterion 3 requires that a comprobante without a QR be completable
 * by hand. Read-only-by-default is this form's decision, not the
 * endpoint's (ADR 016).
 *
 * <h2>It submits all nine fields, every time</h2>
 *
 * Which of them count as corrections is decided by the server, by
 * comparing against the database. So this form does not track what was
 * touched, and cannot get it wrong — and confirming without changing
 * anything writes no audit rows at all.
 */

type FieldSpec = {
  /** The name the API takes, camelCase, same as in the response. */
  name: string
  /** The key in the `sources` map, snake_case, same as in the jsonb column. */
  sourceKey: string
  label: string
  hint?: string
  /** Spans both columns on a wide screen. */
  wide?: boolean
  /** Only `issueDate` gets a date picker; the rest are plain text. */
  type?: 'text' | 'date'
}

/**
 * The nine fields, in the order somebody reads a comprobante: who issued
 * it, which document it is, when, and for how much.
 *
 * Exactly the nine the API accepts. The four other QR fields the schema
 * holds are not here because the API does not return them, and a form
 * cannot ask somebody to review a value it was never shown.
 */
const FIELDS: FieldSpec[] = [
  {
    name: 'issuerBusinessName',
    sourceKey: 'issuer_business_name',
    label: 'Razón social del emisor',
    hint: 'El único dato que el QR no trae: sale del texto o del OCR, así que es el que más conviene mirar.',
    wide: true,
  },
  { name: 'issuerCuit', sourceKey: 'issuer_cuit', label: 'CUIT del emisor' },
  { name: 'invoiceTypeCode', sourceKey: 'invoice_type_code', label: 'Tipo (código ARCA)' },
  { name: 'pointOfSale', sourceKey: 'point_of_sale', label: 'Punto de venta' },
  { name: 'invoiceNumber', sourceKey: 'invoice_number', label: 'Número' },
  { name: 'issueDate', sourceKey: 'issue_date', label: 'Fecha de emisión', type: 'date' },
  { name: 'total', sourceKey: 'total', label: 'Importe total' },
  { name: 'currency', sourceKey: 'currency', label: 'Moneda' },
  { name: 'cae', sourceKey: 'cae', label: 'CAE' },
]

/** Sources the form locks by default, because they are exact. */
const EXACT: FieldSource[] = ['QR']

export function ReviewForm({ job, onConfirmed }: { job: Job; onConfirmed: () => void }) {
  const sources = job.fields?.sources ?? {}

  // Seeded from the job once, by jobId: re-seeding on every render of a
  // polled query would wipe what the person is typing mid-keystroke.
  const initial = useMemo(() => valuesOf(job), [job.jobId])
  const [values, setValues] = useState<Corrections>(initial)
  const [unlocked, setUnlocked] = useState<Set<string>>(new Set())

  const confirm = useConfirm(job.jobId)

  function isLocked(field: FieldSpec): boolean {
    const source = sources[field.sourceKey]
    return !!source && EXACT.includes(source) && !unlocked.has(field.name)
  }

  function unlock(name: string) {
    setUnlocked(new Set(unlocked).add(name))
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    confirm.reset()
    // Empty string means "clear this field". The API treats blank and
    // null the same way, so the form does not have to decide which.
    await confirm.mutateAsync(values).then(onConfirmed).catch(() => undefined)
  }

  const changed = FIELDS.filter((field) => values[field.name] !== initial[field.name])

  return (
    <form className="card" onSubmit={submit}>
      <h2>Revisar antes de confirmar</h2>
      <p className="muted">
        Cada campo dice de dónde salió. Los que vienen del QR son datos oficiales de
        ARCA y están bloqueados; podés desbloquearlos si hace falta.
      </p>

      {confirm.error && <Notice kind="error">{messageFor(confirm.error)}</Notice>}

      <div className="fields">
        {FIELDS.map((field) => {
          const locked = isLocked(field)
          return (
            <div className={`field ${field.wide ? 'wide' : ''}`} key={field.name}>
              <div className="field-head">
                <label htmlFor={field.name}>{field.label}</label>
                <span>
                  <SourceBadge source={sources[field.sourceKey]} />
                  {locked && (
                    <button
                      type="button"
                      className="quiet"
                      style={{ padding: '2px 8px', minHeight: 'auto', marginLeft: 6 }}
                      onClick={() => unlock(field.name)}
                    >
                      editar
                    </button>
                  )}
                </span>
              </div>
              <input
                id={field.name}
                type={field.type ?? 'text'}
                readOnly={locked}
                value={values[field.name] ?? ''}
                onChange={(event) =>
                  setValues({ ...values, [field.name]: event.target.value })
                }
              />
              {field.hint && <div className="field-note">{field.hint}</div>}
            </div>
          )
        })}
      </div>

      <div className="actions">
        <button className="primary" type="submit" disabled={confirm.isPending}>
          {confirm.isPending ? 'Confirmando…' : 'Confirmar'}
        </button>
        <span className="muted" style={{ alignSelf: 'center' }}>
          {changed.length === 0
            ? 'Sin cambios: se confirma tal como se extrajo.'
            : `${changed.length} campo${changed.length > 1 ? 's' : ''} modificado${
                changed.length > 1 ? 's' : ''
              }.`}
        </span>
      </div>
    </form>
  )
}

/**
 * The job's values as the strings the form edits and the API takes.
 *
 * The amount keeps two decimals because that is what the column holds:
 * sending `18450` for a stored `18450.00` would otherwise look like a
 * correction to a client, though the server knows better and would record
 * nothing. Matching here keeps the "sin cambios" label honest.
 */
function valuesOf(job: Job): Corrections {
  const fields = job.fields ?? {}
  return {
    issuerCuit: fields.issuerCuit ?? '',
    invoiceTypeCode: text(fields.invoiceTypeCode),
    pointOfSale: text(fields.pointOfSale),
    invoiceNumber: text(fields.invoiceNumber),
    issueDate: fields.issueDate ?? '',
    total: fields.total === undefined ? '' : fields.total.toFixed(2),
    currency: fields.currency ?? '',
    cae: text(fields.cae),
    issuerBusinessName: fields.issuerBusinessName ?? '',
  }
}

function text(value?: number): string {
  return value === undefined || value === null ? '' : String(value)
}

function messageFor(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 409) {
      return 'Este comprobante ya estaba confirmado.'
    }
    // A 400 from the review endpoint names the field and says why, which
    // is the whole reason the API bothers to build that message.
    return error.message
  }
  return 'No se pudo confirmar.'
}
