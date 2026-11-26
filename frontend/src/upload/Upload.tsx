import { useRef, useState } from 'react'

import { ApiError } from '../api/client'
import { useUpload } from '../api/hooks'
import { CameraCapture } from './CameraCapture'
import { JobProgress } from './JobProgress'
import { Notice } from '../ui/Bits'

/**
 * Getting a comprobante in: from the disk, or from the camera.
 *
 * <h2>Two ways in, and they are not the same</h2>
 *
 * The file picker takes whatever is handed to it. The camera flow waits
 * for the QR to decode before it lets you shoot. That difference is
 * deliberate: when somebody is holding a phone over a receipt, telling
 * them the frame will not work is the point of being there — but a PDF
 * with no QR at all still has to get in, because criterion 3 says it is
 * completable by hand and the review form makes that real.
 *
 * <h2>The upload answers before the work happens</h2>
 *
 * `POST /documents` returns 202 with a job id and the extraction runs on
 * the other side of a queue. So this screen hands off to
 * {@link JobProgress}, which polls — and that is the honest shape of the
 * system rather than a spinner pretending the request is slow.
 */
export function Upload() {
  const [jobId, setJobId] = useState<string | null>(null)
  const [cameraOpen, setCameraOpen] = useState(false)
  const [duplicate, setDuplicate] = useState(false)
  const [draggingOver, setDraggingOver] = useState(false)
  const fileInput = useRef<HTMLInputElement>(null)

  const upload = useUpload()

  async function send(file: File) {
    upload.reset()
    setDuplicate(false)
    setCameraOpen(false)
    try {
      const result = await upload.mutateAsync(file)
      if (result) {
        setJobId(result.jobId)
        setDuplicate(result.duplicate)
      }
    } catch {
      // Held by the mutation and rendered below.
    }
  }

  function startOver() {
    setJobId(null)
    setDuplicate(false)
    upload.reset()
    if (fileInput.current) {
      fileInput.current.value = ''
    }
  }

  if (jobId) {
    return (
      <>
        {duplicate && (
          <Notice kind="info">
            Este archivo ya estaba cargado, así que no se duplicó nada: abajo está el
            job que ya tenía.
          </Notice>
        )}
        <JobProgress jobId={jobId} onDone={startOver} />
      </>
    )
  }

  if (cameraOpen) {
    return <CameraCapture onCapture={send} onCancel={() => setCameraOpen(false)} />
  }

  return (
    <div className="card primary">
      <h2>Subir un comprobante</h2>
      <p className="muted">
        Un PDF de factura electrónica, o una foto. Lo que el QR traiga sale exacto; el
        resto se lee del texto y lo revisás antes de confirmar.
      </p>

      {upload.error && <Notice kind="error">{messageFor(upload.error)}</Notice>}

      {/*
        Dragging a file onto the page is the first thing somebody tries on
        a desktop, and before this the browser did its default: opened the
        PDF and navigated away from the app, losing whatever was on screen.
        Both handlers have to preventDefault — dragOver as well as drop —
        or the drop never fires and the navigation happens anyway.
      */}
      <div
        className={`dropzone ${draggingOver ? 'over' : ''}`}
        onDragOver={(event) => {
          event.preventDefault()
          setDraggingOver(true)
        }}
        onDragLeave={() => setDraggingOver(false)}
        onDrop={(event) => {
          event.preventDefault()
          setDraggingOver(false)
          const file = event.dataTransfer.files?.[0]
          if (file) {
            void send(file)
          }
        }}
      >
        <div className="actions" style={{ marginTop: 0, justifyContent: 'center' }}>
          <button
            className="primary"
            type="button"
            onClick={() => fileInput.current?.click()}
            disabled={upload.isPending}
          >
            {upload.isPending ? 'Subiendo…' : 'Elegir un archivo'}
          </button>
          <button
            type="button"
            onClick={() => setCameraOpen(true)}
            disabled={upload.isPending}
          >
            Usar la cámara
          </button>
        </div>
        {/* Only mentioned where it is possible: a phone has nothing to
            drag, and telling somebody to do something they cannot is
            worse than saying nothing. */}
        <div className="field-note dropzone-hint">
          o arrastrá el archivo acá
        </div>
      </div>

      <input
        ref={fileInput}
        type="file"
        /* The three the API accepts, so the picker does not offer what the
           upload would refuse. */
        accept="application/pdf,image/jpeg,image/png"
        hidden
        onChange={(event) => {
          const file = event.target.files?.[0]
          if (file) {
            void send(file)
          }
        }}
      />
    </div>
  )
}

function messageFor(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 413) {
      return 'El archivo pasa los 10 MB.'
    }
    if (error.status === 503) {
      return 'El almacenamiento no está disponible. No se guardó nada, así que podés reintentar.'
    }
    return error.message
  }
  return 'No se pudo subir el archivo.'
}
