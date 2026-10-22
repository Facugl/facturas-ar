# 006 — Dos servicios sobre una base: por qué no son microservicios

**Estado:** aceptada
**Fecha:** 2026-10-17

## Contexto

El sistema final tiene dos deployables —`upload-api-service` y
`ocr-worker-service`— que **comparten la misma base PostgreSQL**. Para
buena parte de la literatura eso es un antipatrón con nombre propio:
monolito distribuido.

Conviene decirlo antes de que alguien lo descubra.

## Decisión

Dos servicios, una base. Y la separación **no es por bounded context sino
por perfil de recursos**.

El worker necesita CPU, memoria y una imagen Docker con Tesseract y
OpenCV instalados: unos 400 MB de binarios nativos. La API necesita
atender requests HTTP rápido. Son cargas distintas que escalan distinto y
se despliegan distinto, y por eso son dos procesos.

No son dos dominios. El `Job` que crea la API es el mismo `Job` que
actualiza el worker, y partirlo en dos modelos con un contrato entre
ellos sería complejidad sin dueño.

## Consecuencias

**Hay que decirlo explícitamente.** Presentado como decisión consciente
es una fortaleza; descubierto por quien revisa, una debilidad.

**El worker no publica evento de respuesta** (ver
[001](001-db-compartida-sin-evento-de-respuesta.md)). Escribe directo a
la base, que es lo coherente con compartirla.

**Un cambio de esquema afecta a los dos.** Es el costo real de la
decisión, y con un solo desarrollador es irrelevante. Con equipos
independientes dejaría de serlo.

**`shared-domain` existe por esto.** Las entidades viven en un módulo
común porque los dos servicios las usan contra la misma base.

## Cuándo habría que revisarla

Si el worker necesitara escalar a varias réplicas con su propio ciclo de
despliegue, o si apareciera un segundo equipo. Ninguna de las dos es el
caso hoy, y diseñar para ellas sería pagar por adelantado algo que puede
no llegar nunca.

## Alternativas descartadas

**Una sola aplicación.** No permite escalar el OCR aparte de la API, y
obliga a que la API cargue 400 MB de binarios que no usa.

**Microservicios de verdad, con base por servicio.** Exigiría sincronizar
el estado del `Job` por mensajería en las dos direcciones. Es
complejidad real a cambio de una independencia que nadie necesita.
