# 001 — DB compartida, sin evento de respuesta del worker

**Estado:** aceptada · pendiente de implementar (Fase 2)
**Fecha:** 2026-10-17

## Contexto

En la Fase 2 la API publica `job.extraction.requested` y el worker lo
consume. Queda por decidir cómo vuelve el resultado.

Dos opciones: el worker publica `job.extraction.completed` y la API lo
consume para escribir, o el worker escribe directo a la base compartida.

## Decisión

**El worker escribe directo.** No hay evento de respuesta.

## Consecuencias

**El valor de la mensajería asíncrona ya está demostrado** con el evento
de entrada: desacople, reintentos, dead-letter, idempotencia. Un segundo
círculo agrega complejidad sin enseñar nada nuevo.

**Menos piezas que pueden fallar.** Un evento de respuesta necesita su
propio exchange, su propia cola, su propio consumidor idempotente y su
propia DLQ.

**Es coherente con [006](006-dos-servicios-una-base.md).** Si los dos
servicios comparten la base, pretender que se comunican solo por mensajes
es una ficción que cuesta mantener.

**La API no se entera sola de que terminó.** No importa: el frontend ya
hace polling sobre `GET /jobs/:id`, que es lo que necesita de todas
formas para mostrar el estado.

## Cuándo habría que revisarla

Con varios workers escalando independientemente de la API, o si otro
sistema necesitara enterarse de que una extracción terminó. Ahí el evento
de respuesta pasaría de complejidad a necesidad.

## Alternativas descartadas

**Evento de respuesta `job.extraction.completed`.** Correcto en una
arquitectura de microservicios de verdad. Acá serían cuatro piezas más
para resolver un problema que no existe con una sola base.
