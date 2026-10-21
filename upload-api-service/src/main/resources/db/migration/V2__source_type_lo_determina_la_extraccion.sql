-- ────────────────────────────────────────────────────────────────
--  V2 — source_type pasa a ser nullable
--
--  Al escribir la ingesta quedó claro que la columna estaba mal ubicada
--  en el tiempo. Distinguir PDF_TEXT de PDF_SCANNED exige abrir el
--  archivo con PDFBox y ver si trae texto, y eso es trabajo de la
--  extracción, no de la subida. En el momento del upload lo único que se
--  conoce es el MIME type, que no alcanza.
--
--  Las opciones eran guardar PDF_SCANNED como valor provisorio y
--  corregirlo después, o admitir que todavía no se sabe. Lo primero deja
--  un dato falso en la base durante el tiempo que tarde la extracción, y
--  cualquier consulta que mire la columna en el medio da mal.
--
--  Además, en la Fase 2 la API ya no va a tener PDFBox en el classpath:
--  clasificar en el upload sería una dependencia que hay que desarmar
--  más adelante.
--
--  NULL significa "todavía no se analizó", y es información útil: un
--  Job en EXTRACTED con source_type NULL es una anomalía detectable.
-- ────────────────────────────────────────────────────────────────

ALTER TABLE documents ALTER COLUMN source_type DROP NOT NULL;

COMMENT ON COLUMN documents.source_type IS
    'Lo completa la extracción, no la subida. NULL = todavía no se analizó.';
