# 009 — El QR se detecta en el cliente, no en el servidor

**Estado:** aceptada · pendiente (Fase 1, semana 4)
**Fecha:** 2026-11-01
**Revisa:** [ADR 004](004-qr-primero-ocr-de-respaldo.md)

## Contexto

El [ADR 004](004-qr-primero-ocr-de-respaldo.md) cierra diciendo que el
riesgo del sistema se mudó: ya no es la precisión del OCR, es **la tasa
de detección del QR en fotos de celular**. El criterio de aceptación del
MVP puso un número a ese riesgo: QR detectado en **≥ 85%** de las fotos.

Ese número nunca se midió. Se estimó.

Se midió ahora, pasando comprobantes reales por `ExtractionOrchestrator`
con la estrategia de QR ya implementada —la misma que corre en
producción, con `TRY_HARDER` y los dos binarizadores.

| Comprobante | Formato | Resultado |
|---|---|---|
| Factura B, vendedor de Mercado Libre | **PDF** | ✅ los 12 campos |
| Factura A, proveedor (×2 fotos) | foto | ❌ sin QR encontrado |
| Factura, comercio local (foto + primer plano) | foto | ❌ sin QR encontrado |
| Factura, comercio local (×2 fotos) | foto | ❌ sin QR encontrado |
| Factura, comercio local (foto + primer plano) | foto | ⚠️ QR leído, payload vacío |
| Factura de luz (foto + primer plano del QR) | foto | ⚠️ sin QR de ARCA (el QR es de pago) |

**Un PDF: 100% de los campos. Ocho fotos: cero campos.** Entre esas ocho
hay cuatro primeros planos, sacados apuntando al código.

La causa no es la resolución, es la **densidad**. El QR de ARCA codifica
~268 caracteres, así que necesita una matriz grande; impreso en dos
centímetros de papel térmico y fotografiado con el papel curvado, no le
quedan píxeles por módulo para que ZXing resuelva la grilla. La prueba
está en la factura de luz: su QR de pago, de 55 caracteres, se leyó sin
problema **en una foto de la misma clase**. El tamaño del papel es el
mismo; lo que cambia es cuánta información hay que meter ahí.

Y el preprocesamiento no lo arregla. El deskew y la binarización
recuperan contraste, no información que la cámara no captó.

> El hallazgo apareció porque el arnés de prueba imprimía solo "falla sin
> QR legible" y se corrigió para mostrar también las anomalías. Sin eso,
> el caso del payload vacío se habría contado como una foto más que no
> decodificó, y la conclusión habría sido la misma por el motivo
> equivocado.

## Decisión

**La detección del QR se mueve al navegador, en vivo, antes de que la
foto se suba.**

El celular enfoca el comprobante, el browser intenta decodificar cuadro a
cuadro con la [`BarcodeDetector`
API](https://developer.mozilla.org/en-US/docs/Web/API/BarcodeDetector)
(con `jsQR` de respaldo donde no exista), y **el botón de confirmar solo
se habilita cuando el QR se leyó**. La foto viaja junto con el texto ya
decodificado.

Eso convierte un problema de calidad de imagen en un problema de
interacción: el usuario corrige el encuadre con la respuesta delante,
en vez de descubrir media hora después que la foto no servía.

**El servidor sigue decodificando igual, y no le cree al cliente.** La
estrategia de QR no se toca: se corre sobre la imagen recibida como
hasta ahora. El texto que manda el cliente es una *pista*, y entra por el
mismo `ArcaQrDecoder` que todo lo demás — versión 1, campos obligatorios,
dígito verificador del CUIT. Lo que no se puede hacer es tratarlo como
equivalente:

- si el servidor lee el QR de la imagen → `FieldSource.QR`, confianza 1.0
- si solo lo trae el cliente y valida → `FieldSource.QR_CLIENT`,
  confianza 0.95, visible en el formulario de revisión

La diferencia importa porque un payload que el servidor no pudo
corroborar contra el píxel es, en el peor caso, un dato que el usuario
escribió. No es una catástrofe —el formulario de revisión existe
precisamente para que el usuario corrija a mano— pero decir "esto salió
del QR, confianza 1.0" cuando nadie lo vio en la imagen sería mentir en
el único lugar donde el sistema promete exactitud.

## Consecuencias

**El criterio de aceptación cambia y queda medido, no estimado.** El
"≥ 85% de las fotos" se reemplaza por dos criterios separados: 100% de
los campos sobre PDF con QR legible (medido: se cumple), y **la foto se
acepta solo si el QR se leyó en el cliente**, que es un criterio
verificable en vez de un porcentaje esperanzado.

**El OCR deja de ser la respuesta a este problema.** Era tentador decir
"si la foto no da QR, lo resuelve el OCR en la semana 5". No lo resuelve:
el papel térmico borroso que no deja leer el QR tampoco deja leer el
texto, y el OCR entrega exactamente la imprecisión que el QR venía a
evitar. El OCR queda para lo que siempre fue: comprobantes **sin** QR
—anteriores a 2021, no electrónicos, cupones— y la razón social.

**Hay trabajo que se mueve de fase.** La detección en vivo entra en la
semana 4, junto con el frontend que ya estaba planificado. No es alcance
nuevo: es la pantalla de subida, hecha bien.

**La métrica principal se parte en dos.** `invoice.qr.detection.rate`
deja de ser un número: necesita el tag `source` (`server` / `client` /
`none`). Un sistema donde el 90% de los QR los lee el cliente y el 10% el
servidor está sano; el promedio de los dos no dice nada.

**Aparece un caso de emisor roto que hay que reportar bien.** Uno de los
comprobantes trae el QR de ARCA con el parámetro `p` **vacío**: 33
caracteres, la URL y nada más. Ese comprobante no se puede constatar
desde su propio código, y no hay nada que recuperar. El decodificador ya
lo clasificaba como `Malformed`, que es lo correcto; se le agregó un
mensaje propio, porque "el contenido no es JSON válido" manda a quien lea
la anomalía a buscar un bug de parseo que no existe. Es la primera vez
que un dato real ejercita esa rama del diseño.

## Alternativas descartadas

**Insistir en el servidor: upscaling, recorte por regiones, más
binarizadores.** Queda una carta por jugar —cortar la imagen en mosaicos
y decodificar cada uno, que a veces rescata un QR que se pierde en la
imagen completa— y vale un timebox de medio día. Pero no cambia el
diagnóstico: cuando el módulo del QR mide menos de dos píxeles en la
foto, no hay algoritmo que invente los que faltan. Y aunque funcionara,
el usuario seguiría enterándose del fracaso después de subir.

**Mover el OCR al frente.** Es mucho trabajo, hereda toda la imprecisión
del OCR y contradice el ADR 004 por el motivo equivocado: el QR no falló
como fuente de datos, falló el canal por el que llega la imagen.

**Aceptar que las fotos son un camino degradado y empujar PDFs.** Honesto
y barato, pero le saca al producto el caso de uso que lo justifica. El
monotributista que recibe un ticket en papel no tiene un PDF que subir —
y ese es exactamente el usuario del README.

**Pedirle al usuario que acerque más la cámara.** Es lo que ya hace
cualquiera, y las cuatro fotos en primer plano de la tabla de arriba son
el resultado. Sin respuesta inmediata, "acercate más" es adivinar.
