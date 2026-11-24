import type { FieldSource, JobStatus } from '../api/types'

/**
 * The small pieces more than one screen needs.
 *
 * The two badge components are the ones that matter: they are how the
 * project's central idea — that every field knows where it came from —
 * reaches the screen. Everything else here is formatting.
 */

const STATUS_LABEL: Record<JobStatus, string> = {
  PENDING: 'En cola',
  PROCESSING: 'Procesando',
  EXTRACTED: 'A revisar',
  CONFIRMED: 'Confirmado',
  FAILED: 'Falló',
  DEAD_LETTER: 'Sin procesar',
  DUPLICATE: 'Duplicado',
}

export function StatusBadge({ status }: { status: JobStatus }) {
  return (
    <span className={`status ${status.toLowerCase()}`}>{STATUS_LABEL[status]}</span>
  )
}

/**
 * What a source means, in words, because the three-letter code means
 * nothing to the person reading it.
 *
 * The distinction the whole design turns on is the first two versus the
 * last two: `QR` is data ARCA signed, and `OCR` is a machine's guess at
 * what a photo says. A form that showed them the same way would be hiding
 * the one thing it exists to tell.
 */
const SOURCE_LABEL: Record<FieldSource, { short: string; title: string }> = {
  QR: { short: 'QR', title: 'Del código QR de ARCA: dato oficial, exacto' },
  PDF_TEXT: {
    short: 'texto',
    title: 'Leído del texto del PDF con reglas de parseo. Conviene mirarlo',
  },
  OCR: {
    short: 'OCR',
    title: 'Reconocido de la imagen. Es lo menos confiable: revisalo',
  },
  USER: { short: 'vos', title: 'Lo escribiste vos durante la revisión' },
}

export function SourceBadge({ source }: { source?: FieldSource }) {
  if (!source) {
    return (
      <span className="badge none" title="La extracción no encontró este dato">
        vacío
      </span>
    )
  }
  const { short, title } = SOURCE_LABEL[source]
  return (
    <span className={`badge ${source.toLowerCase()}`} title={title}>
      {short}
    </span>
  )
}

export function Notice({
  kind,
  children,
}: {
  kind: 'error' | 'ok' | 'info'
  children: React.ReactNode
}) {
  return (
    <div className={`notice ${kind}`} role={kind === 'error' ? 'alert' : undefined}>
      {children}
    </div>
  )
}

export function Spinner() {
  return <span className="spinner" aria-hidden />
}

/**
 * An amount the way it is written in Argentina: thousands with a dot,
 * decimals with a comma.
 *
 * The same reason the CSV uses `;` as its separator — a number formatted
 * the other way around is read as a different number, and this one is
 * money.
 */
export function formatAmount(value?: number | string | null): string {
  if (value === null || value === undefined) {
    return '—'
  }
  const amount = typeof value === 'string' ? Number(value) : value
  if (Number.isNaN(amount)) {
    return String(value)
  }
  return amount.toLocaleString('es-AR', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })
}

/** `dd/mm/yyyy`, which is what AFIP uses and what the CSV writes. */
export function formatDate(iso?: string | null): string {
  if (!iso) {
    return '—'
  }
  const [year, month, day] = iso.split('-')
  return day && month && year ? `${day}/${month}/${year}` : iso
}

export function formatDateTime(iso?: string | null): string {
  if (!iso) {
    return '—'
  }
  return new Date(iso).toLocaleString('es-AR', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}

/** Today as `yyyy-mm-dd`, in the browser's timezone and not UTC. */
export function today(): string {
  const now = new Date()
  const local = new Date(now.getTime() - now.getTimezoneOffset() * 60_000)
  return local.toISOString().slice(0, 10)
}
