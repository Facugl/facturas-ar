# 012 — Una extracción fallida se ackea; la DLQ es para lo que no se puede procesar

**Estado:** aceptada · implementada
**Fecha:** 2026-11-23

## Contexto

El consumidor usa ack manual (`acknowledge-mode: manual`), así que cada
mensaje termina en una de dos: `basicAck`, y el broker lo olvida, o
`basicNack(requeue=false)`, y el broker lo rutea a la dead-letter queue.

La pregunta es qué hace con una extracción que falló: un PDF corrupto,
una imagen que ningún motor puede leer, un QR ilegible. La respuesta
intuitiva es nack — falló, y para eso está la DLQ.

Hay un detalle que cambia todo. `ExtractComprobante` ya **atrapa sus
propias fallas** y las escribe en el job como `FAILED`, que es un estado
de reposo del que se encarga el reintentador ([ADR 003](003-estados-del-job.md)).
Cuando el consumidor vuelve del `execute`, el resultado —bueno o malo—
ya está registrado y commiteado.

## Decisión

**Una extracción fallida se ackea.** El mensaje hizo su trabajo: entregó
un pedido que se llevó a cabo, y el resultado está anotado.

**La DLQ es para un mensaje que este consumidor no puede procesar en
absoluto:**

- un body que no es el payload que dice ser,
- una versión para la que no fue escrito, o sin versión,
- una base de datos a la que no puede llegar — o sea, cuando la
  extracción ni siquiera pudo registrar su propio fracaso.

Y una tercera categoría que **no es ninguna de las dos**: un tipo de
evento desconocido se **ackea y se descarta**. Llegó bien; simplemente no
somos su lector.

| Situación | Qué hace | Por qué |
|---|---|---|
| Extracción OK | ack | Obvio |
| Extracción falló, `Job` → `FAILED` | **ack** | El resultado está escrito. El reintentador es el dueño |
| Payload ilegible | nack, sin requeue | Ninguna reentrega lo va a hacer legible |
| Versión ausente o desconocida | nack, sin requeue | Asumir la forma del payload es la falla que la versión existe para evitar |
| Tipo de evento desconocido | **ack**, descartar | No es una falla. Es un mensaje de otro |
| La base no responde | nack, sin requeue | El trabajo no se hizo **ni** se anotó |

## Consecuencias

**La misma falla no se trackea en dos lugares.** Nackear una extracción
fallida pondría el mismo trabajo en la DLQ *además* de dejarlo en
`FAILED`, y a partir de ahí los dos registros pueden no coincidir: el
reintentador vuelve a poner el job en `PENDING`, el relay publica un
evento nuevo, y en la DLQ queda un mensaje viejo que ya no corresponde a
nada.

**La DLQ significa una sola cosa:** "este mensaje no se pudo procesar".
Una cola donde cae todo lo que salió mal no se puede mirar; una donde
cae solo lo inprocesable, sí.

**`requeue=false`, siempre.** Reencolar un mensaje que va a fallar igual
es un bucle caliente: el broker lo reentrega tan rápido como pueda, para
siempre, y el consumidor no hace otra cosa.

**El ack va después del commit, nunca antes.** Ackear primero haría que
una caída a mitad de la extracción pierda el mensaje para siempre,
dejando un job en `PROCESSING` sin nada que lo reprocese. Ackear último
hace que una caída termine en reentrega, que el claim atómico
([ADR 007](007-claim-atomico.md)) absorbe. De las dos fallas solo una
tiene vuelta, así que no es una preferencia.

**Un tipo nuevo de evento no necesita despliegue coordinado.** Si un
tipo desconocido se nackeara, agregar `job.extraction.completed` llenaría
la DLQ de mensajes perfectamente válidos hasta que todos los
consumidores se actualicen.

**La DLQ ya tiene consumidor** (`DeadLetterListener`, 2026-11-23). Y la
decisión de esta página le dio su forma: como una extracción fallida se
ackea, lo único que llega ahí son mensajes inprocesables, y un mensaje
inprocesable casi siempre lo es porque su **body** no se pudo leer. Así
que el consumidor no puede sacar el `jobId` del payload — lo saca del
`message-id`, que es una propiedad de AMQP y sobrevive:

```
message-id ──► outbox_event.id ──► aggregate_id ──► el job
```

Funciona solo porque el [011](011-el-envelope-viaja-en-las-propiedades-de-amqp.md)
puso el id del evento en el campo estándar en vez de adentro del
payload. No se diseñó para esto.

**Y los `FAILED` los levanta el reaper, no una cola.** Es el corolario
directo: si una extracción fallida se ackea, la fila del job es el único
registro de que un intento falló, así que el que la tiene que leer es
algo que mira la base. `RecoverJobs` lo hace cada minuto. Eso corrigió
el diseño de reintentos del README, que los mandaba por la DLQ.

## Alternativas descartadas

**Nack en cualquier falla.** Lo intuitivo. Pone la misma falla en dos
registros que pueden divergir, y convierte la DLQ en un lugar donde no se
puede distinguir "esta foto está borrosa" de "este mensaje está roto",
que son problemas de dos personas distintas.

**Que `ExtractComprobante` propague sus excepciones y el consumidor
decida.** Más limpio en apariencia: el que sabe de mensajería decide qué
hacer con la falla. Pero `ExtractComprobante` tiene que escribir `FAILED`
igual —lo necesita el reintentador, y lo necesitaba antes de que
existiera la cola— así que propagarla además significa que la falla se
reporta dos veces, no una vez mejor.

**Ack automático (el default de Spring).** Spring confirma el mensaje al
volver del método. Más simple y pierde el mensaje si el proceso muere a
mitad de la extracción, que es exactamente el criterio de aceptación 8
del README.
