# 011 — El envelope viaja en las propiedades de AMQP, no dentro del body

**Estado:** aceptada · implementada
**Fecha:** 2026-11-23

## Contexto

El [ADR 005](005-transactional-outbox.md) dejó el outbox guardando el
**payload serializado** en una columna `jsonb`, con el tipo, la versión y
el id del evento en columnas aparte. Eso es correcto para almacenarlo: el
mensaje tiene que seguir diciendo lo que decía cuando se escribió, aunque
la clase que lo originó haya cambiado.

Al conectar RabbitMQ hay que decidir qué forma tiene el mensaje en el
cable. El contrato que el README venía especificando era un solo
documento JSON:

```json
{
  "eventId": "...", "eventType": "job.extraction.requested",
  "eventVersion": 1, "occurredAt": "...",
  "payload": { "jobId": "...", ... }
}
```

El problema aparece al implementarlo. El payload ya está guardado como
**texto JSON**. Serializar el envelope como objeto lo mete como un campo
de tipo string, así que el body queda:

```json
{"eventId":"...","payload":"{\"jobId\":\"...\"}"}
```

Doble codificado, ilegible en la consola del broker, y el payload hay que
parsearlo y volverlo a serializar para armarlo.

## Decisión

El **payload es el body, verbatim**. El envelope se mapea a las
propiedades que AMQP ya tiene para eso.

| Envelope | AMQP | Por qué esa |
|---|---|---|
| `eventId` | `message-id` | El campo estándar de identidad. Una reentrega lleva el mismo, que es lo que la hace reconocible como el mismo evento |
| `eventType` | `type` + routing key | La routing key **es** el tipo de evento, así el broker rutea por el mismo valor por el que el consumidor discrimina |
| `eventVersion` | header | AMQP no tiene campo para esto |
| `occurredAt` | `timestamp` | Cuándo pasó el hecho. El broker no lo setea: si no va acá, no queda registrado en ninguna parte |
| `payload` | body | Sin tocar |

## Consecuencias

**Los bytes que se guardaron son los bytes que se mandan.** No hay
reencodeado entre el outbox y el cable, así que el payload no puede
cambiar de forma en el camino. Es una propiedad verificable, y
`RabbitMessagingTest` la verifica a nivel de bytes — no comparando JSON
equivalentes, comparando `byte[]`.

**Se lee en la consola de RabbitMQ.** Abrir la cola y ver el payload sin
desescapar nada es la mitad de cómo se depura esto.

**No hace falta ningún `MessageConverter`.** No queda nada que
convertir, así que no hay una capa más que configurar ni que entender.

**El consumidor lee el tipo y la versión antes de tocar el body**, que es
lo que hay que hacer de todos modos. Con el envelope adentro del body,
para saber si podés leer el mensaje tenés que parsearlo primero.

**El contrato del README cambió, y queda dicho.** La sección 8 muestra
ahora la tabla de arriba en vez del JSON anidado. Un contrato publicado
que se corrige en silencio es peor que uno que nunca se publicó.

**Queda atado a AMQP.** Si el transporte cambiara a algo sin propiedades
por mensaje —un archivo, un `COPY` a una tabla— el envelope habría que
serializarlo en algún lado. Es una deuda real y es barata: la conversión
vive en dos métodos, `messageFor` y `envelopeOf`.

## Alternativas descartadas

**El envelope como un solo JSON, con el payload anidado como objeto
crudo.** Es lo que el README especificaba, y se puede: armar un
`ObjectNode` y hacer `set("payload", mapper.readTree(guardado))`. Dos
cosas en contra. Una, el payload se parsea y se vuelve a serializar, así
que lo que viaja no es garantizadamente lo que se guardó. Dos, la
conversión necesita Jackson, y el módulo `shared-events` —que es donde
viviría— se definió sin ninguna dependencia a propósito.

**El envelope como JSON y el payload como string escapado.** Lo que sale
de serializar el record tal cual. Funciona y es lo más fácil de escribir;
el body queda ilegible y con el payload codificado dos veces.

**Solo el `jobId` en el body, sin envelope.** El mensaje más chico
posible, y el consumidor va a buscar el resto a la base. Descartada por
lo que se pierde: sin `eventId` no hay forma de reconocer una reentrega,
y sin versión no hay forma de evolucionar el contrato.
