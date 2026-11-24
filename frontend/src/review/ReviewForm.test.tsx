import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ReviewForm } from './ReviewForm'
import type { Job } from '../api/types'

/**
 * The review form, which is the screen the whole project points at.
 *
 * <p>Three things here would break silently, and each is a test:
 *
 * <ul>
 *   <li><strong>The badges.</strong> A field whose source stopped being
 *       shown still renders perfectly; it just stops telling the person
 *       which values are ARCA's and which are a guess, which is the one
 *       thing this form exists to say.</li>
 *   <li><strong>The lock on QR fields.</strong> If it inverted, a stray
 *       keystroke could rewrite a CAE with no warning.</li>
 *   <li><strong>Submitting all nine fields.</strong> The server computes
 *       the diff (ADR 016), so a form that quietly sent only what it
 *       thought had changed would be re-introducing exactly the bug that
 *       decision removes.</li>
 * </ul>
 */
describe('el formulario de revision', () => {
  let fetchMock: ReturnType<typeof vi.fn>

  /** A comprobante whose QR decoded: eight exact fields, one parsed. */
  const withQr: Job = {
    jobId: 'job-1',
    status: 'EXTRACTED',
    originalFilename: 'factura.pdf',
    qrDetected: true,
    fields: {
      issuerCuit: '30-71234567-1',
      invoiceTypeCode: 6,
      pointOfSale: 3,
      invoiceNumber: 1201,
      issueDate: '2026-03-02',
      total: 18450,
      currency: 'PES',
      cae: 70417054367476,
      issuerBusinessName: 'PANADERA LA ESPIGA SA',
      sources: {
        issuer_cuit: 'QR',
        invoice_type_code: 'QR',
        point_of_sale: 'QR',
        invoice_number: 'QR',
        issue_date: 'QR',
        total: 'QR',
        currency: 'QR',
        cae: 'QR',
        issuer_business_name: 'PDF_TEXT',
      },
    },
  }

  /** A comprobante the cascade read nothing from: the criterion 3 case. */
  const withoutQr: Job = {
    jobId: 'job-2',
    status: 'EXTRACTED',
    originalFilename: 'foto.jpg',
    qrDetected: false,
    fields: { sources: {} },
  }

  function renderForm(job: Job) {
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    return render(
      <QueryClientProvider client={client}>
        <ReviewForm job={job} onConfirmed={() => undefined} />
      </QueryClientProvider>,
    )
  }

  /** What the last PUT sent, parsed. */
  function sentFields(): Record<string, string> {
    const call = fetchMock.mock.calls.at(-1) as [string, RequestInit]
    return JSON.parse(call[1].body as string).fields
  }

  beforeEach(() => {
    fetchMock = vi.fn(async () =>
      new Response(JSON.stringify({ jobId: 'job-1', status: 'CONFIRMED' }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    )
    vi.stubGlobal('fetch', fetchMock)
    document.cookie = 'XSRF-TOKEN=un-token'
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  // ── Lo que la pantalla muestra ────────────────────────────────────

  it('muestra los nueve campos que la API acepta corregir', () => {
    renderForm(withQr)

    for (const label of [
      'Razón social del emisor',
      'CUIT del emisor',
      'Tipo (código ARCA)',
      'Punto de venta',
      'Número',
      'Fecha de emisión',
      'Importe total',
      'Moneda',
      'CAE',
    ]) {
      expect(screen.getByLabelText(label)).toBeInTheDocument()
    }
  })

  it('carga los valores extraidos, con el importe a dos decimales', () => {
    renderForm(withQr)

    expect(screen.getByLabelText('CUIT del emisor')).toHaveValue('30-71234567-1')
    // 18450 came back as a JSON number; the column is NUMERIC(15,2), so
    // the form shows what is stored and "sin cambios" stays honest.
    expect(screen.getByLabelText('Importe total')).toHaveValue('18450.00')
    expect(screen.getByLabelText('Fecha de emisión')).toHaveValue('2026-03-02')
  })

  /**
   * The badge is the point. `QR` means ARCA signed it; `texto` means a
   * parsing rule produced it and it deserves a look. A form that showed
   * both the same way would be hiding the single most useful thing it
   * knows.
   */
  it('cada campo dice de donde salio, y el que no salio de ningun lado dice vacio', () => {
    renderForm(withQr)
    expect(screen.getAllByTitle(/dato oficial, exacto/)).toHaveLength(8)
    expect(screen.getByTitle(/reglas de parseo/)).toBeInTheDocument()

    renderForm(withoutQr)
    expect(screen.getAllByTitle(/no encontró este dato/)).toHaveLength(9)
  })

  // ── El candado ────────────────────────────────────────────────────

  it('los campos del QR son de solo lectura, y la razon social no', () => {
    renderForm(withQr)

    expect(screen.getByLabelText('CAE')).toHaveAttribute('readonly')
    expect(screen.getByLabelText('Razón social del emisor')).not.toHaveAttribute('readonly')
  })

  it('un comprobante sin QR tiene los nueve campos editables: hay que poder completarlo', () => {
    renderForm(withoutQr)

    expect(screen.getByLabelText('CAE')).not.toHaveAttribute('readonly')
    expect(screen.getByLabelText('Importe total')).not.toHaveAttribute('readonly')
  })

  it('el boton editar desbloquea un campo del QR, uno solo', async () => {
    renderForm(withQr)
    const user = userEvent.setup()

    // Eight locked fields, so eight buttons; the CAE's is the last.
    const unlockers = screen.getAllByRole('button', { name: 'editar' })
    expect(unlockers).toHaveLength(8)

    await user.click(unlockers[unlockers.length - 1])

    expect(screen.getByLabelText('CAE')).not.toHaveAttribute('readonly')
    expect(screen.getByLabelText('Moneda')).toHaveAttribute('readonly')
  })

  // ── Lo que manda ──────────────────────────────────────────────────

  /**
   * All nine, unchanged. This is the shape ADR 016 is built around: the
   * server decides what differs, so the form cannot forget to report
   * something and cannot lie about the old value.
   */
  it('manda los nueve campos aunque no se haya tocado ninguno', async () => {
    renderForm(withQr)
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: 'Confirmar' }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalled())
    const sent = sentFields()
    expect(Object.keys(sent)).toHaveLength(9)
    expect(sent.cae).toBe('70417054367476')
    expect(sent.total).toBe('18450.00')
  })

  it('manda lo corregido, y el PUT va al job con el token de CSRF', async () => {
    renderForm(withQr)
    const user = userEvent.setup()

    const name = screen.getByLabelText('Razón social del emisor')
    await user.clear(name)
    await user.type(name, 'PANADERÍA LA ESPIGA S.A.')
    await user.click(screen.getByRole('button', { name: 'Confirmar' }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalled())
    const [url, options] = fetchMock.mock.calls.at(-1) as [string, RequestInit]
    expect(url).toBe('/jobs/job-1/confirm')
    expect(options.method).toBe('PUT')
    expect((options.headers as Record<string, string>)['X-XSRF-TOKEN']).toBe('un-token')
    expect(sentFields().issuerBusinessName).toBe('PANADERÍA LA ESPIGA S.A.')
  })

  /**
   * Clearing a field has to survive the trip as an empty string: that is
   * what the API reads as "the user cleared it". Dropping empties would
   * make a cleared field unclearable, which is exactly what a review form
   * is for.
   */
  it('vaciar un campo se manda como cadena vacia, no se omite', async () => {
    renderForm(withQr)
    const user = userEvent.setup()

    await user.click(screen.getAllByRole('button', { name: 'editar' }).at(-1)!)
    await user.clear(screen.getByLabelText('CAE'))
    await user.click(screen.getByRole('button', { name: 'Confirmar' }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalled())
    expect(sentFields()).toHaveProperty('cae', '')
  })

  it('el contador dice cuantos campos cambiaron, no cuantos se mandan', async () => {
    renderForm(withQr)
    const user = userEvent.setup()

    expect(screen.getByText(/Sin cambios/)).toBeInTheDocument()

    await user.type(screen.getByLabelText('Razón social del emisor'), '!')

    expect(screen.getByText('1 campo modificado.')).toBeInTheDocument()
  })

  /**
   * The API's 400 names the field and says why — that message is the
   * whole reason it bothers to build one, so the form shows it instead of
   * "error 400".
   */
  it('un 400 de la API se muestra tal como vino, con el campo nombrado', async () => {
    fetchMock.mockImplementation(async () =>
      new Response(
        JSON.stringify({
          status: 400,
          detail: 'issuerCuit: not a valid CUIT — check the verifier digit (got "30-1-1")',
        }),
        { status: 400, headers: { 'Content-Type': 'application/json' } },
      ),
    )
    renderForm(withoutQr)
    const user = userEvent.setup()

    await user.type(screen.getByLabelText('CUIT del emisor'), '30-1-1')
    await user.click(screen.getByRole('button', { name: 'Confirmar' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('not a valid CUIT')
  })
})
