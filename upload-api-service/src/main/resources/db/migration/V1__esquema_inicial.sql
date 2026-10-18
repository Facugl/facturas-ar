-- ────────────────────────────────────────────────────────────────
--  V1 — Esquema inicial
--
--  Alcance v1: monotributista registrando los comprobantes que recibe.
--  Los campos de responsable inscripto (neto, IVA, condición frente al
--  IVA) existen como columnas pero v1 no los completa: están para que
--  v2 no necesite una migración que toque datos ya cargados.
--
--  La tabla outbox_event entra en la Fase 2, junto con la cola.
-- ────────────────────────────────────────────────────────────────


CREATE TABLE users (
    id            UUID         PRIMARY KEY,
    email         VARCHAR(320) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_users_email UNIQUE (email)
);


CREATE TABLE documents (
    id                UUID         PRIMARY KEY,
    user_id           UUID         NOT NULL REFERENCES users (id),
    original_filename VARCHAR(255) NOT NULL,
    storage_key       VARCHAR(512) NOT NULL,
    mime_type         VARCHAR(100) NOT NULL,
    file_size_bytes   BIGINT       NOT NULL,
    content_hash      VARCHAR(64)  NOT NULL,
    source_type       VARCHAR(20)  NOT NULL,
    -- Se completa al terminar la extracción. Alimenta la métrica más
    -- importante del sistema: la tasa de detección de QR.
    qr_detected       BOOLEAN,
    uploaded_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT ck_documents_source_type
        CHECK (source_type IN ('PDF_TEXT', 'PDF_SCANNED', 'IMAGE')),

    -- Primera barrera de deduplicación: el mismo ARCHIVO, byte por byte,
    -- subido dos veces por el mismo usuario. Atrapa el doble clic, no la
    -- duplicación real — para eso está uq_eid_usuario_comprobante.
    --
    -- Es por usuario, no global: dos personas distintas pueden subir
    -- legítimamente el mismo comprobante.
    CONSTRAINT uq_documents_user_content_hash UNIQUE (user_id, content_hash)
);

CREATE INDEX ix_documents_user_uploaded ON documents (user_id, uploaded_at DESC);


CREATE TABLE jobs (
    id                  UUID        PRIMARY KEY,
    document_id         UUID        NOT NULL REFERENCES documents (id),
    status              VARCHAR(20) NOT NULL,
    retry_count         INT         NOT NULL DEFAULT 0,
    max_retries         INT         NOT NULL DEFAULT 3,
    error_message       TEXT,
    ocr_engine          VARCHAR(50),
    overall_confidence  NUMERIC(4, 3),
    -- Si este job resultó ser un comprobante ya cargado, apunta al job
    -- original para que la UI pueda decir "ya lo subiste el 3 de marzo"
    -- en vez de un error sin contexto.
    duplicate_of_job_id UUID        REFERENCES jobs (id) ON DELETE SET NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at          TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,

    -- La máquina de estados, al nivel más bajo posible. Si una transición
    -- inválida se escapa del código, acá se frena.
    CONSTRAINT ck_jobs_status CHECK (status IN (
        'PENDING',      -- único estado procesable: el worker toma desde acá
        'PROCESSING',
        'EXTRACTED',
        'CONFIRMED',
        'DUPLICATE',    -- el comprobante ya estaba cargado por este usuario
        'FAILED',       -- reposo: espera que el reintentador lo vuelva a PENDING
        'DEAD_LETTER'
    )),
    CONSTRAINT ck_jobs_retry_count CHECK (retry_count >= 0),

    -- DUPLICATE sin puntero al original sería una pista a medias
    CONSTRAINT ck_jobs_duplicate_coherente CHECK (
        (status = 'DUPLICATE' AND duplicate_of_job_id IS NOT NULL)
        OR (status <> 'DUPLICATE' AND duplicate_of_job_id IS NULL)
    )
);

CREATE INDEX ix_jobs_document ON jobs (document_id);

-- Soporta el claim atómico del worker:
--   UPDATE jobs SET status='PROCESSING' WHERE id=? AND status='PENDING'
CREATE INDEX ix_jobs_status ON jobs (status) WHERE status IN ('PENDING', 'PROCESSING');


CREATE TABLE extracted_invoice_data (
    job_id                  UUID PRIMARY KEY REFERENCES jobs (id),

    -- Denormalizado desde documents.user_id. Dos motivos independientes:
    --   1. Es la única forma de expresar "este comprobante ya lo cargó
    --      este usuario" como restricción de base.
    --   2. El export filtra por usuario y rango de fechas; sin esto hay
    --      que joinear jobs y documents solo para saber de quién es.
    -- Es la clase segura de denormalización: el dueño de un documento
    -- no cambia nunca.
    user_id                 UUID NOT NULL REFERENCES users (id),

    -- ── Campos del QR de ARCA (RG 4892) ──
    -- Exactos cuando hay QR legible. Nullable porque un comprobante sin
    -- QR o ilegible igual genera una fila: el usuario la completa a mano.
    issuer_cuit             BIGINT,
    invoice_type_code       INT,              -- tabla oficial: 1=Fact A, 6=B, 11=C...
    point_of_sale           INT,
    invoice_number          BIGINT,
    issue_date              DATE,
    total                   NUMERIC(15, 2),
    currency                VARCHAR(3),       -- "PES", "DOL", ...
    exchange_rate           NUMERIC(19, 6),   -- 6 decimales según la especificación
    recipient_doc_type      INT,              -- 80=CUIT, 86=CUIL, 96=DNI, 99=sin identificar
    recipient_doc_number    NUMERIC(20),
    cae                     BIGINT,
    auth_code_type          VARCHAR(1),       -- "E"=CAE, "A"=CAEA

    -- ── Campo del texto embebido o del OCR ──
    issuer_business_name    VARCHAR(255),

    -- ── Preparados para v2 (responsable inscripto). v1 no los completa ──
    net_amount              NUMERIC(15, 2),
    vat_amount              NUMERIC(15, 2),
    recipient_vat_condition VARCHAR(40),

    -- ── Trazabilidad: permite reparsear sin volver a correr Tesseract ──
    qr_payload_raw          JSONB,
    raw_ocr_text            TEXT,
    field_sources           JSONB,   -- {"total": "QR", "issuer_business_name": "OCR"}
    field_confidence        JSONB,   -- {"issuer_cuit": 1.0, "issuer_business_name": 0.6}

    CONSTRAINT ck_eid_auth_code_type
        CHECK (auth_code_type IS NULL OR auth_code_type IN ('E', 'A'))
);

-- ── Deduplicación por identidad fiscal del comprobante ──
--
-- (CUIT emisor, tipo, punto de venta, número) es lo que identifica al
-- comprobante ante ARCA, y el QR nos lo da exacto. Es una clave de
-- deduplicación mucho más fuerte que el hash del archivo: atrapa el caso
-- de tener el PDF del mail Y la foto del impreso, o el de un PDF
-- regenerado por el portal del proveedor que difiere en un byte de
-- metadata.
--
-- Por usuario, no global: dos personas pueden cargar el mismo
-- comprobante legítimamente (una pareja con la factura de luz).
--
-- El WHERE deja afuera las extracciones fallidas, que tienen todo en
-- NULL y no deben chocar entre sí.
--
-- La detección real va en la aplicación (consulta antes de insertar, y
-- marca el Job como DUPLICATE con un mensaje útil). Este índice es la
-- red de seguridad contra la carrera de dos subidas simultáneas: ahí la
-- violación se captura y se convierte en DUPLICATE igual.
CREATE UNIQUE INDEX uq_eid_usuario_comprobante
    ON extracted_invoice_data (user_id, issuer_cuit, invoice_type_code,
                               point_of_sale, invoice_number)
 WHERE issuer_cuit       IS NOT NULL
   AND invoice_type_code IS NOT NULL
   AND point_of_sale     IS NOT NULL
   AND invoice_number    IS NOT NULL;

-- El export filtra por usuario y rango de fecha de emisión
CREATE INDEX ix_eid_user_issue_date ON extracted_invoice_data (user_id, issue_date);


CREATE TABLE field_corrections (
    id              UUID        PRIMARY KEY,
    job_id          UUID        NOT NULL REFERENCES jobs (id),
    field_name      VARCHAR(50) NOT NULL,
    original_value  TEXT,       -- lo que produjo la extracción (puede ser NULL)
    corrected_value TEXT,       -- lo que escribió el usuario
    corrected_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_field_corrections_job   ON field_corrections (job_id);
CREATE INDEX ix_field_corrections_field ON field_corrections (field_name);


-- ────────────────────────────────────────────────────────────────
--  Nota: un Job en DUPLICATE no tiene fila en extracted_invoice_data.
--
--  Los datos ya están en el job original, al que apunta
--  duplicate_of_job_id. Insertarlos de nuevo chocaría contra
--  uq_eid_usuario_comprobante, que es justamente lo que define al
--  duplicado. El archivo subido se conserva igual (RN-06): lo que no
--  se duplica es el dato extraído, no el documento.
-- ────────────────────────────────────────────────────────────────
