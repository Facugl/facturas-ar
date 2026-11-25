# 018 — El preprocesado del OCR es solo deskew

**Estado:** aceptada · implementada
**Fecha:** 2026-11-25
**Relacionada:** [ADR 002](002-tesseract-y-no-paddleocr.md) · [ADR 010](010-dos-motores-para-leer-el-qr.md)

## Contexto

El OCR entró último, y con él la pregunta de qué hacerle a la imagen
antes de pasársela a Tesseract. El README decía "deskew y binarización"
desde el día 1, y toda guía de OCR dice lo mismo más una tercera cosa:
ampliar las páginas chicas, porque el modelo LSTM quiere mayúsculas de
unos 30 px.

Los tres pasos se escribieron. Después se midieron.

## Decisión

**Solo deskew.** El upscale y la binarización se borraron.

### Lo que se midió

Páginas renderizadas, desenfocadas con un box blur de 3×3, comprimidas
en JPEG con calidad 0,45 e iluminadas desde un lado — que es lo que
produce un teléfono de verdad. La pregunta no fue "¿la imagen quedó más
linda?" sino **¿el OCR lee el CUIT, y con cuánta confianza?**

```
foto de 420 px, borrosa, JPEG 0.45, iluminada de un lado
  sin tocar            cuit=true   conf=0.93
  solo upscale         cuit=true   conf=0.91
  solo deskew          cuit=true   conf=0.93
  solo binarise        cuit=FALSE  conf=0.83   <-- rompe la lectura
  upscale+binarise     cuit=true   conf=0.74
  deskew+binarise      cuit=FALSE  conf=0.83
  todo                 cuit=true   conf=0.74

foto de 1400 px, misma degradación
  sin tocar            cuit=true   conf=0.92
  todo                 cuit=true   conf=0.92
```

Y aparte, sobre una página a 4 grados:

```
4 grados: sin deskew NO se lee, con deskew SI
```

### Lo que eso significa

**La binarización con Otsu hacía ilegible el CUIT.** No peor: ausente. Y
el motivo es bueno una vez que se ve — **Tesseract ya binariza por su
cuenta, adaptativamente, por región.** Un umbral global aplicado antes le
quita el gris que iba a usar, y sobre un glifo desenfocado un corte duro
convierte los trazos de un 3 en la forma de un 8. El paso que más
obviamente parecía correcto era el que hacía daño.

**El upscale no pagó nada** y costó un poco. La conclusión es más angosta
que "ampliar no sirve": en estas imágenes los glifos ya eran lo bastante
grandes, y la interpolación cúbica sobre una foto borrosa amplifica el
desenfoque que interpola. Vuelve si una medición sobre fotos reales dice
otra cosa, y el ADR queda acá para que esa medición exista antes del
código.

**El deskew pagó**, y es el único que lo hizo. Una página a 4 grados pasa
de ilegible a legible, y nunca empeora una página derecha porque se
niega a tocarla: si el ángulo estimado es menor a 0,5° o mayor a 20°, la
devuelve igual.

### La tercera vez que pasa lo mismo

Esto es el mismo resultado que el
[ADR 010](010-dos-motores-para-leer-el-qr.md), que probó **catorce**
variantes de preprocesado contra la detección del QR y encontró que
ninguna rescataba una foto que cambiar el decodificador no arreglara
igual. Y antes el [009](009-deteccion-del-qr-en-el-cliente.md) había
concluido de más por medir con un solo lector.

El patrón vale nombrarlo: **el preprocesado se siente como progreso y
casi nunca lo es**, y la única forma de saberlo es medir la cosa que
importa —si el dato sale— en vez de mirar la imagen intermedia y decidir
que está mejor.

## Consecuencias

**La clase quedó en un tercio.** `ImagePreprocessor` hace una cosa, y los
tests que borraron los otros dos pasos siguen ahí: volver a agregarlos
significa borrar una medición en vez de olvidarla.

**El test no puede afirmar lo más fuerte, y lo dice.** La corrida que dio
`cuit=false` fue contra el `THRESH_OTSU` de OpenCV, y ese paso ya no
existe; el Otsu escrito a mano en el test no es la misma operación —
convierte a gris distinto— y da `cuit=true` con menos confianza. Así que
el test afirma lo que sí reproduce: **umbralizar cuesta confianza y no
trae nada**, que ya alcanza para borrar el paso. Afirmar lo otro sería
afirmar algo que ese código no demuestra.

**El idioma es `spa` donde está instalado, `eng` si no**, y la lectura
dice cuál fue. El jar de Tess4J trae `eng` y los nativos de Windows, así
que `eng` funciona en cualquier parte sin configurar nada; `spa` son
~15 MB de binario que no van en git, así que la imagen del worker instala
`tesseract-ocr-spa` y quien desarrolla en Windows obtiene inglés.

Cuánto cuesta eso es menos de lo que parece, y el motivo son los dos
campos por los que esta estrategia existe: un CUIT son dígitos, donde el
modelo de lenguaje no aporta nada, y una razón social son nombres propios
y sufijos legales —`S.A.`, `S.R.L.`— que no son palabras de diccionario
en ningún idioma. Lo que `spa` compra de verdad son los acentos:
`PANADERÍA` en vez de `PANADERIA` o `PANADERlA`. Vale tenerlo, no vale un
binario en el repo, y el formulario de revisión lo corrige igual.

**Ningún test puede depender de cuál corrió**, porque CI y una laptop con
Windows no van a coincidir.

**El `TESSDATA_PREFIX` mal puesto degrada, no mata.** Si la variable
apunta a un directorio que no sirve, el motor cae al `eng` empaquetado en
el jar en vez de quedarse sin OCR: un error de deploy produce un
resultado peor, no la ausencia de resultado.

**La confianza que se guarda es la que midió Tesseract, no el 0,5 de
base.** El javadoc de `FieldSource.OCR` lo prometía desde el primer
commit — *"OCR adjusts it afterwards with what the engine reports"* — y
hasta ahora nada lo cumplía. Un 0,5 constante en cada campo de OCR, sea
un escaneo limpio o una foto oscura, haría de `field_confidence` una
constante, y una constante no es una señal.

**`invoice.ocr.confidence` existe, y es un summary y no un gauge.** Un
gauge guarda el último valor, y el último comprobante no es la pregunta;
lo que se quiere saber es cómo se ve la distribución —si el OCR lee
escaneos limpios en 0,9 o adivina fotos en 0,4— y una distribución
necesita percentiles. Solo se registra cuando el OCR corrió: anotar un
cero por cada comprobante cuyo QR decodificó enterraría las lecturas
reales bajo una pila de ceros de documentos que el OCR nunca tocó.

**Y es la opinión de Tesseract sobre su propia salida, no la probabilidad
de que el texto esté bien.** Vale ser preciso, porque el número va a un
dashboard donde alguien lo va a leer como exactitud.

## Lo que apareció de paso

**Las anomalías no las leía nadie.** Cada estrategia viene armando una
lista de notas desde la Fase 1 — *"el PDF tiene texto pero no se pudo
identificar una razón social"* — y al final del pipeline se descartaban.
Lo encontré porque la estrategia de OCR agregó una nota ahí y la nota no
apareció en ninguna parte.

Ahora se loguean con el `jobId`, una línea, al lado de todo lo demás de
ese job. La que más vale es la segunda: es el dataset que dice qué
layouts no cubren las reglas de parseo, y se estaba tirando.

**El formato de un número dependía del locale de la máquina.** La nota de
confianza salía `ocr confidence 0,92` con coma en una máquina en español y
habría salido con punto en CI. `String.format` con `Locale.ROOT` en vez de
`.formatted()`. Es la misma forma de bug que el separador decimal del CSV
—donde la coma sí se quiere— y que el `0,92` es exactamente lo que no se
quiere en un diagnóstico.

**Y un bug de zona horaria en el historial**, que no es del OCR pero lo
encontró la misma corrida de tests a las 23:45. `GET /jobs` convertía
`LocalDate.now()` —fecha local— usando `ZoneOffset.UTC`: a las 23:45 de
Argentina ya es el día siguiente en UTC, así que un comprobante subido
"hoy" caía fuera del rango de "hoy" y **el historial se vaciaba todas las
noches entre las nueve y medianoche**, volviendo solo a la mañana. La zona
ahora es `America/Argentina/Buenos_Aires`, fija y no
`ZoneId.systemDefault()`: un contenedor corre en UTC salvo que alguien
ponga `TZ`, y el default estaría bien en la laptop y mal en producción.

## Alternativas descartadas

**Dejar los tres pasos "por si acaso".** Es lo que estaba escrito antes de
medir. Uno de los tres rompía lecturas, así que el "por si acaso" tenía
un costo concreto.

**Umbral adaptativo en vez de global** (`adaptiveThreshold`). Más cerca de
lo que Tesseract hace internamente, y por eso mismo: estaría haciendo
peor lo que Tesseract ya hace mejor, sobre la imagen que le va a entregar.
Si alguna vez hay una medición que diga que el adaptativo gana, el lugar
para probarlo es este ADR.

**Deskew por líneas de Hough** en vez de por el rectángulo de área
mínima. Más preciso cuando hay reglas rectas impresas, y un ticket
muchas veces no tiene ninguna. El rectángulo alrededor de la tinta
funciona sobre cualquier página con texto.

**Servicio de OCR aparte, en vez de JNI.** Ya descartada en el
[ADR 002](002-tesseract-y-no-paddleocr.md): Tess4J es un binding, el
deploy entero es una línea de `apt-get`, y un servicio más sería otra
cosa que puede estar caída.

**Empaquetar `spa.traineddata` en el repositorio.** Quince megas de
binario en git para evitar una línea de Dockerfile, y además haría que el
modelo del idioma se versione a mano en vez de venir de la distribución.
