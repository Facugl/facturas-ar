import { useCallback, useEffect, useRef, useState } from 'react'
import jsQR from 'jsqr'

/**
 * Reads a QR out of a live camera feed, frame by frame.
 *
 * <h2>Two engines, and why the order is that way</h2>
 *
 * `BarcodeDetector` is the browser's own, implemented natively — on
 * Android Chrome it is backed by the same platform code the camera app
 * uses, and it is both faster and better at a QR photographed at an
 * angle. It does not exist on Safari or Firefox, so `jsQR` is the
 * fallback: pure JavaScript, slower, works everywhere.
 *
 * Which one answered is reported, not hidden. The project already learned
 * this the expensive way: ADR 009 concluded the server could do no better
 * because ZXing read 1 of 6 photos, and ADR 010 found OpenCV's detector
 * read 4 of the same 6. A measurement that does not say which decoder
 * produced it is a measurement that can be wrong in exactly that way.
 *
 * <h2>What this is worth, honestly</h2>
 *
 * It is a **UX improvement, not the thing that saves the product** —
 * ADR 009's original claim, which ADR 010 refuted. The server decodes the
 * uploaded image independently and does not trust anything from here.
 * What this buys is that the person framing a photo finds out *now*
 * whether it will work, instead of after the upload.
 */

/** The subset of `BarcodeDetector` this uses, since TS does not ship it. */
type BarcodeDetectorLike = {
  detect(source: ImageBitmapSource): Promise<Array<{ rawValue: string }>>
}

type BarcodeDetectorConstructor = new (options: {
  formats: string[]
}) => BarcodeDetectorLike

function nativeDetector(): BarcodeDetectorLike | null {
  const Detector = (
    globalThis as unknown as { BarcodeDetector?: BarcodeDetectorConstructor }
  ).BarcodeDetector
  if (!Detector) {
    return null
  }
  try {
    return new Detector({ formats: ['qr_code'] })
  } catch {
    // Constructible but without the format. Treated as absent.
    return null
  }
}

export type ScanEngine = 'barcode-detector' | 'jsqr'

export type QrReading = {
  text: string
  engine: ScanEngine
  /** How long the camera ran before it read anything, in milliseconds. */
  tookMs: number
}

export type ScannerState = {
  /** Null until something decodes. The gate on the camera flow. */
  reading: QrReading | null
  /** Set when the camera itself could not be opened. */
  error: string | null
  scanning: boolean
  /** Which engine is doing the work, for the measurement. */
  engine: ScanEngine | null
}

/**
 * Drives the camera and the decode loop while `active` is true.
 *
 * @param videoRef the `<video>` the stream is attached to
 * @param active   false tears the camera down — and it has to, because a
 *                 phone leaves the lamp and the indicator on until the
 *                 tracks are stopped
 */
export function useQrScanner(
  videoRef: React.RefObject<HTMLVideoElement | null>,
  active: boolean,
): ScannerState & { reset: () => void } {
  const [reading, setReading] = useState<QrReading | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [engine, setEngine] = useState<ScanEngine | null>(null)
  const [scanning, setScanning] = useState(false)

  // Kept in a ref and not in state: the loop reads it every frame, and
  // state would re-run the effect and restart the camera.
  const foundRef = useRef(false)

  const reset = useCallback(() => {
    foundRef.current = false
    setReading(null)
    setError(null)
  }, [])

  useEffect(() => {
    if (!active) {
      return
    }

    let stream: MediaStream | null = null
    let frame = 0
    let cancelled = false
    const startedAt = performance.now()

    const detector = nativeDetector()
    const using: ScanEngine = detector ? 'barcode-detector' : 'jsqr'
    setEngine(using)

    // One canvas for the whole run. Allocating per frame is what turns a
    // decode loop into a garbage-collection loop on a phone.
    const canvas = document.createElement('canvas')
    const context = canvas.getContext('2d', { willReadFrequently: true })

    async function readFrame(video: HTMLVideoElement): Promise<string | null> {
      if (detector) {
        const codes = await detector.detect(video)
        return codes[0]?.rawValue ?? null
      }
      if (!context || !video.videoWidth) {
        return null
      }
      canvas.width = video.videoWidth
      canvas.height = video.videoHeight
      context.drawImage(video, 0, 0)
      const pixels = context.getImageData(0, 0, canvas.width, canvas.height)
      return jsQR(pixels.data, pixels.width, pixels.height)?.data ?? null
    }

    async function loop() {
      const video = videoRef.current
      if (cancelled || !video || foundRef.current) {
        return
      }
      try {
        const text = await readFrame(video)
        if (text && !cancelled) {
          foundRef.current = true
          setReading({ text, engine: using, tookMs: Math.round(performance.now() - startedAt) })
          return
        }
      } catch {
        // A frame that cannot be decoded is the normal case, not an
        // error: the camera is pointed at a table most of the time.
        // Swallowed per frame so one bad frame does not end the scan.
      }
      frame = requestAnimationFrame(() => void loop())
    }

    async function start() {
      try {
        stream = await navigator.mediaDevices.getUserMedia({
          // The rear camera, which is the one pointed at a piece of
          // paper. `ideal` and not `exact` so a laptop with one webcam
          // still gets a stream instead of an OverconstrainedError.
          video: { facingMode: { ideal: 'environment' } },
        })
        if (cancelled) {
          stream.getTracks().forEach((track) => track.stop())
          return
        }
        const video = videoRef.current
        if (!video) {
          return
        }
        video.srcObject = stream
        await video.play()
        setScanning(true)
        void loop()
      } catch (cause) {
        setError(describe(cause))
      }
    }

    void start()

    return () => {
      cancelled = true
      cancelAnimationFrame(frame)
      setScanning(false)
      // Stopping the tracks is what turns the camera light off. Without
      // it the stream outlives the component and the phone keeps
      // recording — which looks exactly like spyware to a user.
      stream?.getTracks().forEach((track) => track.stop())
    }
  }, [active, videoRef])

  return { reading, error, scanning, engine, reset }
}

/**
 * Says what went wrong in terms the person can act on.
 *
 * The browser's own messages are useless here ("Permission denied"), and
 * the three cases need three different answers: allow it, plug one in, or
 * use HTTPS.
 */
function describe(cause: unknown): string {
  const name = cause instanceof Error ? cause.name : ''
  if (name === 'NotAllowedError') {
    return 'No diste permiso para usar la cámara. Podés habilitarla en el candado de la barra de direcciones, o subir el archivo desde el disco.'
  }
  if (name === 'NotFoundError' || name === 'OverconstrainedError') {
    return 'No se encontró ninguna cámara en este dispositivo.'
  }
  if (!window.isSecureContext) {
    return 'El navegador solo da acceso a la cámara sobre HTTPS (o en localhost).'
  }
  return 'No se pudo abrir la cámara.'
}
