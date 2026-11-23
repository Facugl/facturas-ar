-- ────────────────────────────────────────────────────────────────
--  V3 — Tabla del transactional outbox
--
--  La V1 dejó dicho que esta tabla entraba "en la Fase 2, junto con la
--  cola". Entra antes que la cola, a propósito: el outbox es un patrón
--  de base de datos, no de mensajería. Lo único que necesita saber del
--  broker es que no comparte transacción con Postgres, y eso ya se sabe.
--
--  Lo que resuelve: la fila del Job y el evento que la anuncia se
--  escriben o no se escriben JUNTAS. Sin esto, un broker caído entre el
--  INSERT y el publish deja un Job en PENDING que nadie va a procesar
--  nunca. Ver ADR 005.
-- ────────────────────────────────────────────────────────────────


CREATE TABLE outbox_event (
    id            UUID         PRIMARY KEY,

    -- El id de la entidad que generó el evento (hoy, siempre un jobId).
    -- Sin FK a jobs: el outbox no debe poder bloquear la escritura de la
    -- entidad que lo genera, y los eventos sobreviven a propósito al
    -- borrado de su agregado — si el evento ya salió, pasó.
    aggregate_id  UUID         NOT NULL,

    event_type    VARCHAR(100) NOT NULL,

    -- Viaja en la fila, no hardcodeado en el relay: el día que convivan
    -- la v1 y la v2 del mismo evento, las filas viejas tienen que seguir
    -- diciendo la verdad sobre con qué contrato se escribieron.
    event_version INT          NOT NULL,

    payload       JSONB        NOT NULL,

    -- Cuándo PASÓ el hecho, no cuándo se publicó. La diferencia entre
    -- published_at y este valor es la latencia que introduce el outbox,
    -- y es la métrica que dice si el relay está sano.
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    published_at  TIMESTAMPTZ,

    CONSTRAINT ck_outbox_event_version CHECK (event_version >= 1)
);


-- El relay hace una sola consulta: las pendientes, en orden de llegada.
--
-- Índice PARCIAL. Es la diferencia entre un índice que crece para
-- siempre y uno que mide lo que hay sin publicar — o sea, casi siempre
-- vacío. En una tabla que solo acumula, esto no es una optimización
-- prematura: es lo que evita que la consulta del relay se degrade a
-- medida que la tabla se llena de filas ya publicadas.
CREATE INDEX ix_outbox_event_pendientes
    ON outbox_event (created_at)
 WHERE published_at IS NULL;


COMMENT ON TABLE outbox_event IS
    'Transactional outbox. Se escribe en la misma transacción que el agregado; un relay la vacía contra el transporte. Ver ADR 005.';

COMMENT ON COLUMN outbox_event.published_at IS
    'NULL = pendiente de publicar. El relay solo mira estas filas.';


-- ────────────────────────────────────────────────────────────────
--  Pendiente conocido: las filas publicadas se quedan.
--
--  Hoy sirven de registro de auditoría ("¿este job generó su evento?",
--  "¿cuánto tardó en publicarse?") y el volumen es un evento por
--  comprobante subido, así que no es un problema todavía. Pero crece sin
--  techo, y la poda es trabajo de la Fase 3 junto con las métricas —
--  no se puede decidir cuánto retener sin antes medir.
-- ────────────────────────────────────────────────────────────────
