# 008 — Deduplicación en tres capas

**Estado:** aceptada · implementada
**Fecha:** 2026-10-22

## Contexto

El diseño inicial deduplicaba por `content_hash` del archivo, por
usuario. Alcanza para el doble clic y nada más.

Se escapan dos casos frecuentes. Tener el PDF que llegó por mail **y** la
foto del impreso: dos archivos, una factura. Y volver a descargar el
comprobante del portal del proveedor, que lo regenera con otro timestamp
en la metadata: bytes distintos, misma factura.

La clave correcta es la identidad fiscal —`(CUIT emisor, tipo, punto de
venta, número)`— que es lo que identifica al comprobante ante ARCA. Y
está disponible **gracias al QR** (ver [004](004-qr-primero-ocr-de-respaldo.md)):
hay una clave de deduplicación confiable justamente por haber puesto el
QR primero.

## Decisión

Tres capas, cada una en el momento en que puede saber algo que las otras
no.

**1 — Hash del archivo.** En la subida, antes de guardar nada. Si
acierta, no se sube el archivo ni se crean filas: devuelve el job que ya
existía con `HTTP 200`.

**2 — Identidad fiscal.** En la extracción, después de leer el QR. Acá el
archivo ya está guardado y se conserva (RN-06: un archivo subido nunca se
pierde). Lo que no se duplica es el dato: el `Job` queda en `DUPLICATE`
apuntando al original y no se inserta fila en `extracted_invoice_data`.

**3 — Índice único parcial.** En la base, en el momento del `INSERT`.

```sql
CREATE UNIQUE INDEX uq_eid_usuario_comprobante
    ON extracted_invoice_data (user_id, issuer_cuit, invoice_type_code,
                               point_of_sale, invoice_number)
 WHERE issuer_cuit IS NOT NULL AND ...;
```

El `WHERE` deja afuera las extracciones fallidas: tienen los cuatro
campos en `NULL` y no deben chocar entre sí.

Las tres son **por usuario, no globales**. Dos personas pueden cargar
legítimamente el mismo comprobante: una pareja con la factura de luz, dos
socios con la del proveedor.

## Consecuencias

**`user_id` queda denormalizado en `extracted_invoice_data`.** Es la
única forma de expresar la restricción en la base, y de paso le saca dos
joins a la consulta del export. Es la clase segura de denormalización: el
dueño de un documento no cambia nunca.

**Aparece una asimetría que confunde.** La capa 1 responde `200` con
`duplicate: true`; la capa 2 responde `202` con `duplicate: false` y
después el job dice `DUPLICATE`. No es una contradicción: el campo del
POST responde "¿ya tenía este archivo?", y en ese momento la respuesta
honesta era no.

**La capa 2 depende de que la extracción funcione.** Sin QR legible los
cuatro campos quedan en `NULL` y esa capa no puede opinar. Queda la
capa 1.

**La capa 3 necesita manejo propio.** La violación de restricción llega
como `DataIntegrityViolationException` y hay que convertirla en
`DUPLICATE`, en una transacción nueva porque la que chocó queda
*rollback-only*. Sin eso cae en el manejador genérico, el job queda en
`FAILED`, se reintenta, vuelve a chocar, y muere en `DEAD_LETTER` por
algo que no es un error. **Ese bug existió y se encontró al documentar
este flujo, no al probarlo.**

## Alternativas descartadas

**Unique global sobre la identidad fiscal.** Rechazaría que dos usuarios
distintos carguen el mismo comprobante, que es un caso legítimo.

**Solo el hash.** Es la capa 1 sola: no atrapa la duplicación real.

**Solo el índice único, sin la consulta previa.** El índice decide bien
pero no produce un mensaje útil. La consulta es la que permite decir "ya
cargaste esta factura el 3 de marzo" en vez de un error de restricción.

## Verificación

Cuatro subidas en paralelo del mismo comprobante en archivos distintos:
una `EXTRACTED`, tres `DUPLICATE` apuntando al original, tres carreras
registradas en el log y una sola fila en `extracted_invoice_data`.
