# 005 — Transactional outbox para publicar el evento

**Estado:** aceptada · pendiente de implementar (Fase 2)
**Fecha:** 2026-10-17

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
