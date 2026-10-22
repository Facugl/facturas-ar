# 004 — QR primero, OCR de último recurso

**Estado:** aceptada · implementada
**Fecha:** 2026-10-17

## Contexto

El plan original era un pipeline de OCR: foto → Tesseract → expresiones
regulares → campos con confianza parcial. El criterio de aceptación
hablaba de acertar "más del 70%" de los campos clave, que es lo que se
puede esperar de OCR sobre fotos de celular.

Investigando el dominio apareció la [RG 4892/2020](https://www.afip.gob.ar/fe/qr/documentos/QRespecificaciones.pdf):
desde 2021 **toda factura electrónica argentina lleva un código QR
obligatorio**. Y ese QR no es un link a un sitio de consulta: codifica
los datos del comprobante en JSON, en Base64, dentro de la URL.

```json
{"ver":1,"fecha":"2020-10-13","cuit":30000000007,"ptoVta":10,
 "tipoCmp":1,"nroCmp":94,"importe":12100,"moneda":"DOL","ctz":65,
 "tipoDocRec":80,"nroDocRec":20000000001,"tipoCodAut":"E",
 "codAut":70417054367476}
```

Doce campos, exactos, sin leer un solo píxel de texto.

## Decisión

La extracción es una **cascada de tres estrategias**, no un pipeline de
OCR. Cada campo se queda con la lectura de la fuente más confiable que lo
haya producido.

| # | Estrategia | Confianza | Qué aporta |
|---|---|---|---|
| 1 | QR con ZXing | 1.0 | fecha, CUIT, punto de venta, tipo, número, importe, moneda, cotización, documento del receptor, CAE |
| 2 | Texto embebido con PDFBox | 0.9 | razón social, neto, IVA |
| 3 | OCR con Tess4J | 0.3–0.8 | lo que siga faltando |

El OCR deja de ser el motor del sistema y pasa a ser la red de seguridad.

## Consecuencias

**La precisión deja de depender de Tesseract.** El criterio de aceptación
pasó de ">70% sobre campos clave" a **100% de los campos del QR cuando el
QR es legible**. No es optimismo: un QR es un dato estructurado, o se lee
o no se lee.

**La confianza se calcula en vez de inventarse.** Como quedan los valores
de todas las fuentes y no solo del ganador, si el total del QR coincide
con el leído por OCR ambos se refuerzan; si no coinciden, gana el QR y la
discrepancia se registra.

**La constatación del CAE deja de ser cara.** Con el CAE, el CUIT, el
tipo, el punto de venta, el número, la fecha y el importe en la mano,
validar contra ARCA es una llamada más.

**El preprocesamiento de imagen cambia de objetivo.** El deskew y la
binarización ya no son "para que Tesseract lea mejor" sino, primero, para
que ZXing encuentre el código.

**El riesgo se mudó.** Ya no es la precisión del OCR: es la **tasa de
detección del QR** en fotos de celular. Por eso es la métrica principal
del sistema.

## Lo que solo apareció con una factura real

Dos diferencias entre lo que ARCA documenta y lo que ARCA emite. Las dos
se encontraron corriendo el decodificador contra una Factura B real; los
tests sintéticos no podían encontrarlas porque todos usaban el PDF
oficial como fuente de verdad.

**La URL no es la documentada.**

```
especificación:  www.arca.gob.ar/fe/qr/?p=
factura real:    serviciosweb.afip.gob.ar/genericos/comprobantes/cae.aspx?p=
```

Se dejó de validar la ruta. Fijarla rechazaría comprobantes válidos por
un detalle que la especificación nunca prometió mantener, y no hay motivo
para creer que esas dos sean las únicas en circulación. El control quedó
en el dominio más el payload.

**ARCA no respeta su propio nombre de campo.**

```
especificación:  "tipoCodAut"
factura real:    "tipoCodeAut"    ← una "e" de más
```

El ejemplo que está dentro de ese mismo PDF usa la primera forma. Se
aceptan ambas: confiar solo en la especificación rechaza todas las
facturas en circulación, confiar solo en lo observado rompe el día que lo
arreglen.

## Alternativas descartadas

**Seguir con OCR puro.** Habría significado pelear la precisión de
Tesseract durante semanas para llegar al 70%, teniendo disponible un dato
exacto. El QR es gratis y ya está en el papel.

**Usar solo el QR.** No alcanza: no trae la razón social del emisor, ni
el neto, ni el IVA discriminado. Y los comprobantes anteriores a 2021, o
los no electrónicos, no lo tienen.

**Un servicio de OCR de terceros.** Caro, dependencia externa, y resuelve
peor un problema que el dominio ya resolvió.
