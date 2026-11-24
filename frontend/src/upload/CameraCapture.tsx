import { useRef, useState } from 'react'

import { useQrScanner } from './useQrScanner'
import { Notice, Spinner } from '../ui/Bits'

/**
 * Photographing a comprobante, with the QR being read live.
 *
 * <h2>What the live reading is actually for</h2>
 *
 * Feedback, and nothing else. The server decodes the uploaded image
 * itself and does not trust a word from here — with two decoders, one of
 * which reads photos better than anything available in a browser
 * (ADR 010). What this buys is that somebody holding a phone over a
 * receipt finds out <em>now</em> that the frame will not work, instead of
 * after the upload.
 *
 * ADR 009 originally claimed this was "the decision that saves the
 * product". ADR 010 refuted that by measuring. The feature stays because
 * the feedback is genuinely worth it; the claim does not.
 *
 * <h2>Why the shutter waits for the QR</h2>
 *
 * In this flow, and only in this flow. Pointing a camera at a QR and
 * being allowed to send a frame that did not decode wastes the one
 * advantage of being here: the person could just fix the angle. The file
 * picker next to it has no such gate, because criterion 3 says a
 * comprobante with no QR at all has to get in and be completed by hand.
 *
 * There is an escape hatch all the same — "sacar la foto igual" — because
 * a gate with no way around it turns a blurry QR into a dead end, and the
 * server might read what the browser could not.
 */
export function CameraCapture({
  onCapture,
  onCancel,
}: {
  onCapture: (file: File) => void
  onCancel: () => void
}) {
  const videoRef = useRef<HTMLVideoElement>(null)
  const { reading, error, scanning, engine } = useQrScanner(videoRef, true)
  const [capturing, setCapturing] = useState(false)

  /**
   * Grabs the current frame as a JPEG.
   *
   * At the video's own resolution, not the element's: the element is
   * styled with `object-fit: cover` and reading it at display size would
   * upload a cropped, downscaled version of exactly the detail the
   * server's decoder needs.
   */
  async function shoot() {
    const video = videoRef.current
    if (!video || !video.videoWidth) {
      return
    }
    setCapturing(true)
    try {
      const canvas = document.createElement('canvas')
      canvas.width = video.videoWidth
      canvas.height = video.videoHeight
      canvas.getContext('2d')?.drawImage(video, 0, 0)

      const blob = await new Promise<Blob | null>((resolve) =>
        // 0.92 rather than the 0.8 default: JPEG artefacts land on
        // high-contrast edges, which is precisely what a QR is made of.
        canvas.toBlob(resolve, 'image/jpeg', 0.92),
      )
      if (blob) {
        onCapture(new File([blob], `comprobante-${Date.now()}.jpg`, { type: 'image/jpeg' }))
      }
    } finally {
      setCapturing(false)
    }
  }

  if (error) {
    return (
      <div className="card">
        <Notice kind="error">{error}</Notice>
        <div className="actions">
          <button type="button" onClick={onCancel}>
            Volver y subir un archivo
          </button>
        </div>
      </div>
    )
  }

  return (
    <div className="card">
      <div className="viewfinder">
        {/* muted and playsInline are what let a phone play this at all:
            iOS refuses to autoplay anything else, and without playsInline
            it opens the video full-screen. */}
        <video ref={videoRef} muted playsInline />
        <div className={`guide ${reading ? 'found' : ''}`} />
        <div className="viewfinder-hint">
          {!scanning && (
            <>
              <Spinner /> Abriendo la cámara…
            </>
          )}
          {scanning && !reading && 'Acercá el QR hasta que llene el recuadro'}
          {reading && `QR leído en ${(reading.tookMs / 1000).toFixed(1)} s`}
        </div>
      </div>

      <div className="actions">
        <button className="primary" type="button" onClick={shoot} disabled={!reading || capturing}>
          {capturing ? 'Sacando…' : 'Sacar la foto'}
        </button>
        <button type="button" onClick={shoot} disabled={!scanning || capturing}>
          Sacar la foto igual
        </button>
        <button className="quiet" type="button" onClick={onCancel}>
          Cancelar
        </button>
      </div>

      <div className="field-note">
        {reading
          ? 'El QR decodificó, así que la foto sirve. El servidor lo vuelve a leer por su cuenta: lo de acá es para que no subas una foto que no iba a funcionar.'
          : 'El botón se habilita cuando el QR decodifica. Si no lo logra, podés sacarla igual y que lo intente el servidor, que lee mejor.'}
        {engine && (
          <>
            {' '}
            Decodificador:{' '}
            <code>{engine === 'barcode-detector' ? 'BarcodeDetector' : 'jsQR'}</code>.
          </>
        )}
      </div>
    </div>
  )
}
