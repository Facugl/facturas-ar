/**
 * What the API returns, as the API returns it.
 *
 * Hand-written rather than generated, and kept thin on purpose: these
 * mirror `JobResponse`, `UploadResponse` and `Identity` field for field.
 * Renaming anything here to read better in TypeScript would mean a
 * mapping layer whose only job is to hide that the API said something
 * else.
 */

export type JobStatus =
  | 'PENDING'
  | 'PROCESSING'
  | 'EXTRACTED'
  | 'CONFIRMED'
  | 'FAILED'
  | 'DEAD_LETTER'
  | 'DUPLICATE'

/**
 * Where a field's value came from. Drives the badge on the review form,
 * which is the whole reason the API sends it.
 *
 * `USER` is written when somebody corrects the field: a value typed by
 * hand must stop claiming to be official QR data.
 */
export type FieldSource = 'QR' | 'PDF_TEXT' | 'OCR' | 'USER'

/** The nine fields the review form can show and correct. */
export type Fields = {
  issuerCuit?: string
  invoiceTypeCode?: number
  pointOfSale?: number
  invoiceNumber?: number
  issueDate?: string
  total?: number
  currency?: string
  cae?: number
  issuerBusinessName?: string
  /** Keyed by the snake_case name, which is what the jsonb column holds. */
  sources?: Record<string, FieldSource>
}

export type Job = {
  jobId: string
  status: JobStatus
  originalFilename?: string
  qrDetected?: boolean
  sourceType?: string
  confidence?: number
  duplicateOfJobId?: string
  errorMessage?: string
  fields?: Fields
}

export type Upload = {
  jobId: string
  documentId: string
  status: JobStatus
  duplicate: boolean
  uploadedAt: string
}

export type Identity = {
  id: string
  email: string
}

/**
 * The review form's submission: field name to value, where an empty
 * string clears the field.
 *
 * Values are strings even for the numbers, because that is what the API
 * takes — it is what the audit table stores, and it funnels every value
 * through one parse per field on the server. See ADR 016.
 */
export type Corrections = Record<string, string | null>

/** A job still moving. Worth its own name because polling depends on it. */
export function isInFlight(status: JobStatus): boolean {
  return status === 'PENDING' || status === 'PROCESSING'
}

/**
 * One line of the history list.
 *
 * Everything after `uploadedAt` is optional, and that is the shape of the
 * problem rather than laziness: a job that failed, or one whose QR never
 * decoded, has no amount and no date and still belongs in the list. The
 * file is always kept, so its name is always there.
 */
export type HistoryRow = {
  jobId: string
  status: JobStatus
  originalFilename: string
  uploadedAt: string
  qrDetected?: boolean
  issueDate?: string
  total?: number
  invoiceTypeCode?: number
  /** The human name of the type, resolved by the API. */
  invoiceType?: string
  issuerBusinessName?: string
}

export type History = {
  jobs: HistoryRow[]
  page: number
  size: number
  /** Ignoring the page, so the UI can say "20 de 143". */
  total: number
}
