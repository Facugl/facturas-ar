# Decisiones de arquitectura

Registro de las decisiones que costaron pensar, con el contexto en el que
se tomaron. No documentan qué hace el sistema —para eso está el README—
sino **por qué es así y no de otra forma**, incluidas las alternativas
que se descartaron.

Se escriben cuando la decisión se toma, no al final: el razonamiento se
pierde rápido, y es lo único que sirve cuando hay que revisarla.

| # | Decisión | Estado |
|---|---|---|
| [001](001-db-compartida-sin-evento-de-respuesta.md) | DB compartida, sin evento de respuesta del worker | Aceptada |
| [002](002-tesseract-y-no-paddleocr.md) | Tesseract y no PaddleOCR | Aceptada |
| [003](003-estados-del-job.md) | Los estados del Job y sus transiciones | Aceptada · implementada |
| [004](004-qr-primero-ocr-de-respaldo.md) | **QR primero, OCR de último recurso** | Aceptada · implementada |
| [005](005-transactional-outbox.md) | Transactional outbox para publicar el evento | Aceptada · pendiente (Fase 2) |
| [006](006-dos-servicios-una-base.md) | Dos servicios sobre una base: por qué no son microservicios | Aceptada |
| [007](007-claim-atomico.md) | Claim atómico y una sola puerta de reproceso | Aceptada · implementada |
| [008](008-deduplicacion-en-tres-capas.md) | Deduplicación en tres capas | Aceptada · implementada |

**Formato:** contexto, decisión, consecuencias, alternativas descartadas.
Una página. Si no entra, la decisión probablemente sean dos.
