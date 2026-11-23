# 005 — Transactional outbox para publicar el evento

**Estado:** aceptada · **implementada**
**Fecha:** 2026-10-17 · **implementada:** 2026-11-23 (con RabbitMQ el mismo día)

## Contexto

Cuando llegue la cola, la API tendrá que persistir el `Job` y publicar
`job.extraction.requested`. El camino obvio es hacer las dos cosas
seguidas.

Son **dos sistemas distintos sin transacción común**. Si el broker está
caído, o el proceso muere entre una y otra, queda un `Job` en `PENDING`
que nadie va a procesar nunca y un usuario mirando un "procesando" que no
avanza. Es el problema del *dual-write*.

## Decisión

El evento se escribe en una tabla `outbox_event` **dentro de la misma
transacción** que crea el `Job`. Un relay aparte la vacía contra
RabbitMQ.

```
1. Transacción única:
     INSERT documents + jobs (PENDING)
     INSERT outbox_event (published_at = null)
   COMMIT

2. Relay:
     SELECT ... WHERE published_at IS NULL
     publicar
     UPDATE published_at = now()
```

## Consecuencias

**El `Job` nunca existe sin su evento.** O se guardan los dos o ninguno:
es una sola transacción.

**Si el broker está caído, el evento espera en la tabla.** Se publica
cuando vuelve.

**Puede publicarse dos veces.** Si el relay publica y muere antes del
`UPDATE`, el evento sale de nuevo. Y está bien: el consumidor ya es
idempotente por [007](007-claim-atomico.md). At-least-once de punta a
punta, asumido por diseño.

**La costura ya está puesta.** En Fase 1 la subida publica un evento de
Spring en vez de llamar a la extracción. En Fase 2 ese mismo momento
escribe en el outbox. **La subida no cambia.**

## Alternativas descartadas

**Publicar directo después del commit.** Es el dual-write. Funciona casi
siempre, y el "casi" es un job perdido sin rastro.

**Publicar dentro de la transacción, antes del commit.** Peor: puede
publicarse un evento para un `Job` que después no se guarda, y el worker
busca una fila que no existe.

**Two-phase commit entre Postgres y RabbitMQ.** Resuelve el problema y
trae otros peores: bloqueos, coordinador, operación frágil.

---

## Lo que se implementó (2026-11-23)

El outbox entró **antes que la cola**, y eso fue deliberado: es un
patrón de base de datos, no de mensajería. Lo único que necesita saber
del broker es que no comparte transacción con Postgres, y eso ya se
sabía. Ir en este orden dejó el sistema funcionando de punta a punta en
cada paso, en vez de un corte grande entre "sincrónico" y "asincrónico".

```
UploadComprobante  ──┬── INSERT documents + jobs (PENDING)
   (una transacción)  └── INSERT outbox_event (published_at = null)
                     COMMIT
                        │
                        ├─ DrainOutboxOnUpload ──► OutboxRelay.drain()
                        │     (camino rápido, al commitear)
                        └─ OutboxRelay.sweep()
                              (@Scheduled cada 5s, recuperación)

OutboxRelay.drain()  (transacción propia, REQUIRES_NEW)
    SELECT ... WHERE published_at IS NULL
      ORDER BY created_at LIMIT 100
      FOR UPDATE SKIP LOCKED
    publisher.publish(envelope)     ← OutboxPublisher (el puerto)
      └─ espera la confirmación del broker
    markPublished()
  COMMIT
      │
      ▼
  RabbitMQ: invoice.processing ──key job.extraction.requested──►
              ocr.extraction.queue
      │
      └─ ExtractionRequestListener (@RabbitListener, ack manual)
              └─► ExtractComprobante
```

### Tres decisiones que el ADR original no tenía

| Decisión | Por qué |
|---|---|
| **Dos disparadores, no uno** | El ADR decía "`@Scheduled` … o listener del commit". Son los dos, y no es redundancia: el listener del commit es el camino normal (el evento sale en milisegundos), y el barrido periódico es el de recuperación — publica el evento de una subida cuyo proceso murió justo después del commit, o el backlog de un transporte que estuvo caído. Sin el barrido, el outbox no tendría salida; sin el camino rápido, cada subida pagaría la espera al vacío |
| **`FOR UPDATE SKIP LOCKED`** | Con dos disparadores hay dos relays que pueden correr a la vez, y sin el lock los dos leerían las mismas filas y publicarían todo dos veces. `FOR UPDATE` solo haría que el segundo espere al primero, convirtiendo relays concurrentes en una fila; `SKIP LOCKED` es lo que hace que se reparta el trabajo. Es también lo que deja la puerta abierta a más de una réplica de la API sin tocar nada |
| **`REQUIRES_NEW` en la transacción del relay** | El camino rápido corre dentro del callback *after-commit* de la subida, y ahí la transacción de la subida ya commiteó pero sigue registrada como la actual. Una transacción `REQUIRED` se habría sumado a esa, y los `UPDATE published_at` se habrían escrito en una transacción terminada: sin error, sin efecto, y cada evento publicado de nuevo en el barrido siguiente |

### Una corrección al contrato del evento

El payload especificado en el README llevaba `sourceType`, y **no se
puede completar en la subida**. Distinguir `PDF_TEXT` de `PDF_SCANNED`
exige abrir el archivo con PDFBox, que es trabajo de la extracción — el
mismo motivo por el que la `V2` hizo nullable esa columna. Mandar un
valor adivinado sería peor que no mandar nada, porque el consumidor lo
creería. El campo salió del contrato.

### El broker llegó el mismo día

El `OutboxPublisher` nació con una implementación in-process, como
andamio, y duró unas horas: `RabbitOutboxPublisher` la reemplazó y la
in-process se borró. Que el reemplazo no haya tocado nada arriba del
puerto es la prueba de que el puerto estaba en el lugar correcto.

Dos decisiones que salieron de conectarlo, cada una con su ADR:
[011](011-el-envelope-viaja-en-las-propiedades-de-amqp.md) (el envelope
viaja en las propiedades de AMQP, no dentro del body) y
[012](012-una-extraccion-fallida-se-ackea.md) (una extracción fallida se
ackea; la DLQ es para lo que no se puede procesar).

Y una tercera, que vive en el publisher: **el relay espera la
confirmación del broker** antes de marcar el evento publicado. Sin eso,
un `send` que vuelve sin excepción solo significa que los bytes llegaron
al socket — un broker que muere antes de escribir el mensaje se lo lleva
mientras el relay ya marcó la fila. El evento se perdería sin rastro, que
es la única falla que este ADR existe para descartar. Cuesta un
round-trip por evento, y el caso normal es un evento por corrida.

### Lo que todavía no está

**El worker separado.** El consumidor está escrito como consumidor de
cola —lee tipo y versión antes de confiar en el body, no hace nada que no
pueda hacer dos veces— pero todavía corre adentro del
`upload-api-service`. Mudarlo a `ocr-worker-service` es la semana 3.

**Nada: el consumidor de la DLQ y el reaper entraron el 2026-11-23.** Y
los dos reintentan **escribiendo en el outbox**, nunca publicando: un
reintento que publicara al broker fuera de la transacción que devuelve el
job a `PENDING` sería este mismo dual-write, en el peor lugar posible —
el camino que corre cuando algo ya falló. Ver
[012](012-una-extraccion-fallida-se-ackea.md).

**El veneno en el relay.** Un evento que el broker rechace siempre hace
fallar su lote para siempre y bloquea todo lo que está detrás, porque el
lote es una transacción. La solución es conteo de reintentos por evento,
igual que del lado del consumidor. Un `try/catch` por evento no lo
arreglaría: saltearía el evento y lo dejaría pendiente igual.

**La poda.** Las filas publicadas se quedan. Hoy sirven de auditoría y
el volumen es un evento por comprobante subido, pero crece sin techo.
Cuánto retener no se puede decidir sin medir primero, así que va con las
métricas de la Fase 3.

### Medido contra el stack real, con el broker caído

Lo que el ADR prometía desde octubre, comprobado:

| | |
|---|---|
| `POST /documents` con RabbitMQ detenido | **202 Accepted**, `status: PENDING` |
| La fila en `outbox_event` | `published_at IS NULL`, esperando |
| Al volver el broker, 52 s después | el barrido la publicó |
| El job | `EXTRACTED`, 3 s más tarde |

Nada se perdió y el usuario nunca vio un error. Eso también destapó un
problema: el upload sobrevivía **por accidente**. El listener corre en la
fase `afterCompletion` de la sincronización, y Spring loguea y descarta
las excepciones de ahí — así que las subidas seguían devolviendo 202
mientras el log se llenaba de `ERROR
TransactionSynchronization.afterCompletion` con stack trace, que se lee
como si las subidas estuvieran fallando. Ahora `DrainOutboxOnUpload`
atrapa su propia falla: la resiliencia deja de depender de qué fase usa
el framework, y el log dice lo que realmente pasó.
