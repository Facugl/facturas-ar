# 007 — Claim atómico y una sola puerta de reproceso

**Estado:** aceptada · implementada
**Fecha:** 2026-10-18

## Contexto

RabbitMQ entrega *at-least-once* y el outbox puede publicar el mismo
evento dos veces. La entrega duplicada no es un caso borde: es una
certeza del diseño.

El planteo original era una lista negra: descartar el mensaje si el `Job`
ya está en `EXTRACTED`, `CONFIRMED` o `FAILED`. Tenía dos problemas.

`FAILED` estaba en esa lista, pero `FAILED` es justamente el estado que
el reintento vuelve a encolar. Tal como estaba, un job que fallaba y se
reintentaba se descartaba al llegar.

Y `PROCESSING` **no** estaba en la lista, así que un mensaje duplicado
para un job que ya está corriendo se procesaba igual: dos workers sobre
el mismo documento.

## Decisión

**Un `Job` se procesa solo desde `PENDING`**, y se toma con un `UPDATE`
condicional:

```sql
UPDATE jobs SET status = 'PROCESSING', started_at = now()
 WHERE id = :jobId AND status = 'PENDING';
```

Una fila afectada significa que este worker se quedó con el job. Cero
significa que se lo llevó otro, o que no estaba en estado tomable: en ese
caso hace `ack` y no trabaja.

**Todo camino de reproceso devuelve el job a `PENDING` antes de
republicar.** El reintento del DLQ, el reaper de jobs colgados y el
reintento manual del usuario hacen los tres lo mismo. Una sola puerta de
entrada al trabajo.

## Consecuencias

**Lista blanca, no lista negra.** "Procesá solo si está en `PENDING`" es
cerrado por defecto: un estado agregado después queda afuera hasta que
alguien decida lo contrario. Hay un test parametrizado que recorre todos
los estados menos `PENDING` y exige que ninguno sea procesable.

**Tiene que ser un `UPDATE` condicional, no un `SELECT` seguido de un
`UPDATE`.** Entre las dos sentencias hay una ventana en la que dos
réplicas leen `PENDING` y las dos arrancan. Esa ventana no se cierra con
reintentos ni con más código: se cierra dejando que la base decida.

**`FAILED` pasa a ser estado de reposo.** No se procesa desde ahí; espera
a que el reintentador lo devuelva a `PENDING`. Eso resuelve además un
problema que la lista negra no podía: una copia vieja del mensaje
original es indistinguible del reintento deliberado si se mira solo el
estado.

**Aparece el reaper.** Si `PROCESSING` deja de ser procesable, un worker
que muere a mitad deja el job trabado para siempre. Hace falta una tarea
programada que devuelva a `PENDING` los jobs en `PROCESSING` más viejos
que el timeout. El umbral tiene que estar holgado respecto del peor caso
real de extracción, o el reaper se roba jobs que todavía corren.

**El claim no es `startProcessing()`.** Ese método mantiene consistente
el objeto en memoria; la protección contra concurrencia es el `UPDATE`.
Confundirlos es fácil y está comentado en el código por eso.

## Alternativas descartadas

**Sacar `FAILED` de la lista negra y dejar el resto.** Arregla el
síntoma. Deja `PROCESSING` procesable —el bug de verdad— y no permite
distinguir el reintento deliberado de un duplicado viejo.

**Bloqueo pesimista (`SELECT ... FOR UPDATE`).** Funciona, pero sostiene
el bloqueo durante toda la extracción, que es casi un segundo. El
`UPDATE` condicional dura microsegundos.
