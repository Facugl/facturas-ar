# 005 — Transactional outbox para publicar el evento

**Estado:** aceptada · **implementada** (transporte in-process; RabbitMQ pendiente)
**Fecha:** 2026-10-17 · **implementada:** 2026-11-23

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
    markPublished()
  COMMIT
      │
      └─ ExtractionRequestListener ──► ExtractComprobante
            (hoy @TransactionalEventListener; en Fase 2, @RabbitListener)
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

### Lo que todavía no está

**El broker.** El `OutboxPublisher` tiene una sola implementación,
`InProcessOutboxPublisher`, que despacha un evento de Spring dentro del
mismo proceso. Hace el viaje de ida y vuelta por JSON de verdad, así que
un payload que no sobreviva la serialización falla hoy y no el día que
entre RabbitMQ. Lo que no reproduce es el límite de proceso: no hay
`ack`, no hay reentrega de un mensaje sin confirmar, y un consumidor que
explota se lleva la transacción del relay en vez de que el mensaje vaya
a una DLQ.

**El veneno en la cola.** Un evento que no se pueda publicar nunca
—un payload que el transporte rechace— hace fallar su lote para siempre
y bloquea todo lo que está detrás, porque el lote es una transacción.
La solución es conteo de reintentos por evento y una salida a
dead-letter, que es la semana 3 de la Fase 2. Un `try/catch` por evento
no lo arreglaría: saltearía el evento y lo dejaría pendiente igual.

**La poda.** Las filas publicadas se quedan. Hoy sirven de auditoría y
el volumen es un evento por comprobante subido, pero crece sin techo.
Cuánto retener no se puede decidir sin medir primero, así que va con las
métricas de la Fase 3.
