# 003 — Los estados del Job y sus transiciones

**Estado:** aceptada · implementada
**Fecha:** 2026-10-18

## Contexto

Un comprobante pasa por varios momentos: encolado, procesándose, listo
para revisar, confirmado, fallado, agotado. Hacen falta estados, y hacen
falta reglas sobre qué transición es válida.

El riesgo no son los estados sino **dónde vive la lógica**. Repartida en
`if`s por los servicios, alcanza con que un camino se olvide de un caso
para que el reproceso deje de ser seguro.

## Decisión

Siete estados, con las transiciones válidas **dentro del enum
`JobStatus`** y en ningún otro lado.

```
PENDING ──claim──> PROCESSING ──> EXTRACTED ──> CONFIRMED
                        │
                        ├── ya cargado ──> DUPLICATE
                        ├── error ───────> FAILED ──┐
                        └── colgado ────────────────┤
                                                    │
                   retry_count < max ───────────────┴──> PENDING
                   retry_count >= max ──────────────────> DEAD_LETTER
                   DEAD_LETTER ──reintento manual──────> PENDING
```

`CONFIRMED` y `DUPLICATE` son terminales.

La entidad `Job` no expone setter de estado. Cada transición es un método
con nombre —`startProcessing()`, `markExtracted()`, `markDuplicateOf()`,
`requeue()`— y todos pasan por `requireTransitionTo`, que valida y lanza
si la transición es inválida.

## Consecuencias

**Una transición inválida falla donde ocurre**, con los dos estados en el
mensaje, y deja el job intacto. Hay un test que verifica que el objeto no
queda a medio transicionar.

**El `CHECK` de la base es la última línea.** Si una transición inválida
se escapara del código, la restricción la frena ahí.

**No hay estado de "baja confianza".** El formulario de revisión siempre
es editable, así que separar un `EXTRACTED_LOW_CONFIDENCE` no cambiaría
nada: la confianza va por campo y se muestra, no decide el flujo.

**`DUPLICATE` exige `duplicate_of_job_id`.** Un duplicado sin puntero al
original es una pista a medias, y hay un `CHECK` que lo impide.

## Alternativas descartadas

**Un setter de estado con validación afuera.** Es lo mismo pero
opcional: cualquiera puede saltearse la validación, y alguien lo hará.

**Una máquina de estados con librería.** Para siete estados y ocho
transiciones, la dependencia cuesta más que lo que ahorra.
