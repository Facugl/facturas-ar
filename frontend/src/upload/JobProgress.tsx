import { ReviewForm } from '../review/ReviewForm'
import { useJob } from '../api/hooks'
import { isInFlight, type Job } from '../api/types'
import { Notice, Spinner, StatusBadge, formatAmount, formatDate } from '../ui/Bits'

/**
 * Follows one job from the queue to the review form.
 *
 * <h2>Polling, because the system really is asynchronous</h2>
 *
 * The upload answered 202 and the extraction happens in another process
 * on the other side of RabbitMQ, so there is nothing to await — only a
 * state to watch. {@link useJob} polls every 1.5 s while the status is
 * `PENDING` or `PROCESSING` and stops on its own afterwards. The API
 * caches that read in Redis for thirty seconds precisely because this
 * screen exists, so most of these polls never touch Postgres.
 *
 * <h2>Five endings, and each one says something different</h2>
 *
 * A job does not simply succeed or fail. `EXTRACTED` opens the review
 * form; `CONFIRMED` is done; `DUPLICATE` means the same comprobante was
 * already loaded, which is not an error; `FAILED` will be retried by the
 * reaper without anybody asking; `DEAD_LETTER` will not. Collapsing them
 * into "ok / error" would throw away the part the user needs: whether to
 * do something.
 */
export function JobProgress({ jobId, onDone }: { jobId: string; onDone: () => void }) {
  const { data: job, isLoading, error } = useJob(jobId)

  if (isLoading) {
    return (
      <div className="card">
        <Spinner /> Buscando el comprobante…
      </div>
    )
  }

  if (error || !job) {
    return (
      <div className="card">
        <Notice kind="error">No se encontró el comprobante.</Notice>
        <button type="button" onClick={onDone}>
          Volver
        </button>
      </div>
    )
  }

  return (
    <>
      <div className="card">
        <div className="field-head">
          <h2>{job.originalFilename ?? 'Comprobante'}</h2>
          <StatusBadge status={job.status} />
        </div>

        {isInFlight(job.status) && (
          <p className="muted">
            <Spinner /> La extracción corre en otro proceso, del otro lado de la cola.
            Esta pantalla pregunta cada segundo y medio.
          </p>
        )}

        {job.status === 'EXTRACTED' && <Extracted job={job} />}

        {job.status === 'CONFIRMED' && (
          <Notice kind="ok">
            Confirmado. Ya entra en el export del período de su fecha de emisión.
          </Notice>
        )}

        {job.status === 'DUPLICATE' && (
          <Notice kind="info">
            Es el mismo comprobante que uno ya cargado — mismo CUIT, tipo, punto de
            venta y número. El archivo se guardó igual; el dato no se duplicó.
          </Notice>
        )}

        {job.status === 'FAILED' && (
          <Notice kind="error">
            La extracción falló{job.errorMessage ? `: ${job.errorMessage}` : ''}. El
            reintentador lo vuelve a encolar solo; no hace falta subirlo de nuevo.
          </Notice>
        )}

        {job.status === 'DEAD_LETTER' && (
          <Notice kind="error">
            Se agotaron los reintentos. El archivo está guardado y se puede reprocesar
            a mano.
          </Notice>
        )}

        <div className="actions">
          <button type="button" onClick={onDone}>
            Subir otro
          </button>
        </div>
      </div>

      {job.status === 'EXTRACTED' && <ReviewForm job={job} onConfirmed={() => undefined} />}
    </>
  )
}

/**
 * What the extraction found, in one line, skipping what it did not find.
 *
 * <p>The first version always printed all three parts, so a comprobante
 * with only a razón social read
 * {@code "PANADERÍA LA ESPIGA S.A. · — · $—"}. Two em-dashes that say
 * "there is nothing here" next to a form that is about to say the same
 * thing nine times.
 */
function summaryOf(fields: NonNullable<Job['fields']>): string {
  const parts = [
    fields.issuerBusinessName,
    fields.issueDate ? formatDate(fields.issueDate) : null,
    fields.total === undefined ? null : `$${formatAmount(fields.total)}`,
  ].filter(Boolean)

  return parts.length === 0
    ? 'La extracción no encontró ningún dato: está todo por completar.'
    : parts.join(' · ')
}

/**
 * Says what kind of document it turned out to be, in words.
 *
 * <p>{@code sourceType} is a database enum — {@code PDF_TEXT},
 * {@code PDF_SCANNED}, {@code IMAGE} — and it was being printed raw:
 * "No se leyó ningún QR (PDF_TEXT)". That is an implementation detail
 * wearing a parenthesis, and it tells the person nothing they can act on.
 */
function describeSource(sourceType?: string): string {
  switch (sourceType) {
    case 'PDF_TEXT':
      return ', pero el PDF traía texto'
    case 'PDF_SCANNED':
      return ': el PDF es una imagen escaneada'
    case 'IMAGE':
      return ' en la foto'
    default:
      return ''
  }
}

/**
 * What the cascade found, before the form.
 *
 * Says whether the QR decoded, because that one fact predicts everything
 * about the review that follows: with a QR, eight of the nine fields are
 * exact and there is nothing to check. Without one, all of them are
 * guesses or empty.
 */
function Extracted({ job }: { job: NonNullable<ReturnType<typeof useJob>['data']> }) {
  const fields = job.fields ?? {}
  return (
    <>
      {job.qrDetected ? (
        <Notice kind="ok">
          El QR decodificó, así que los datos de ARCA son exactos. Solo la razón social
          se leyó del texto.
        </Notice>
      ) : (
        <Notice kind="info">
          No se leyó ningún QR{describeSource(job.sourceType)}. Lo que haya
          salió del texto, y lo que falte se completa a mano acá abajo.
        </Notice>
      )}
      <p className="muted">{summaryOf(fields)}</p>
    </>
  )
}
