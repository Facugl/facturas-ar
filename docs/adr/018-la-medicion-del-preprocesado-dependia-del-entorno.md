# 018 — La medición del preprocesado dependía del entorno

**Estado:** aceptada · implementada
**Fecha:** 2026-11-25
**Relacionada:** [ADR 002](002-tesseract-y-no-paddleocr.md) · [ADR 009](009-deteccion-del-qr-en-el-cliente.md) · [ADR 010](010-dos-motores-para-leer-el-qr.md)

> **Cambié de opinión tres veces en el mismo día, y las tres con
> números.** Primero *"solo deskew"*, después *"los tres pasos"*, y
> finalmente lo que dice este documento. El historial de git tiene las
> dos versiones anteriores. Lo que importa no es la decisión —que es
> chica— sino por qué costó tres intentos llegar a ella.

## Contexto

El OCR entró último y con él la pregunta de qué hacerle a la imagen antes
de pasársela a Tesseract. El README decía "deskew y binarización" desde el
día 1, y toda guía de OCR agrega un tercero: ampliar las páginas chicas,
porque el modelo LSTM quiere mayúsculas de unos 30 px.

Los tres se escribieron. Después se midieron, y ahí se puso interesante.

## Decisión

**`prepare` = upscale → deskew.** Sin binarizar.

Medido en los dos entornos que existen, sobre páginas renderizadas,
desenfocadas con un box blur de 3×3, comprimidas en JPEG 0,45 e
iluminadas desde un lado. La pregunta siempre fue **¿sale el CUIT, y con
cuánta confianza?** — no "¿la imagen quedó más linda?".

| foto de 420 px | Windows + `eng` | Linux + `spa` |
|---|---|---|
| sin tocar | 0,93 | **0,77** |
| solo upscale | 0,91 | **0,93** |
| solo deskew | 0,93 | 0,77 |
| solo binarise | `cuit=FALSE` · 0,83 | 0,81 |
| upscale + binarise | 0,74 | **0,70** |
| **prepare (up+des)** | **0,91** | **0,93** |

| foto de 1400 px | Windows + `eng` | Linux + `spa` |
|---|---|---|
| sin tocar | 0,92 | 0,93 |
| solo binarise | 0,92 | **0,80** |
| **prepare (up+des)** | **0,92** | **0,93** |

Y aparte, sobre una página a 4 grados: en Windows **no se lee sin
deskew** y sí con él; en Linux se lee de las dos formas.

Lo que eso sostiene, paso por paso:

- **El upscale paga, y mucho, donde importa.** En Linux con `spa` sube la
  confianza de 0,77 a 0,93 sobre una página chica. En Windows cuesta dos
  puntos. Producción es Linux con `spa`.
- **El deskew es neutro en confianza y rescata una página torcida.** No
  empeora nada nunca, porque se niega a tocar una página derecha: si el
  ángulo estimado es menor a 0,5° o mayor a 20°, devuelve la imagen igual.
- **Binarizar daña en los dos entornos.** `cuit=false` en Windows; trece
  puntos menos en la foto grande en Linux. Y combinado con el upscale es
  lo peor de la tabla en los dos lados: 0,74 y 0,70.

El motivo de lo último es bueno una vez que se ve: **Tesseract ya binariza
por su cuenta, adaptativamente y por región.** Un umbral global aplicado
antes le quita el gris que iba a usar, y sobre un glifo desenfocado un
corte duro convierte los trazos de un 3 en la forma de un 8.

## Cómo costó tres intentos

### Intento 1: medí en mi máquina y concluí para todas

Windows, con el `eng.traineddata` que viene empaquetado en el jar de
Tess4J. Resultado: Otsu rompía lecturas, el upscale no aportaba nada, el
deskew rescataba. **Borré el upscale y la binarización**, con tests que
documentaban por qué y un ADR escrito titulado *"El preprocesado del OCR
es solo deskew"*.

### Intento 2: CI midió en otra parte y me contradijo

Los mismos tests en Linux contra `spa`, que es el traineddata que instala
la imagen del worker. Fallaron exactamente las dos aserciones que habían
justificado las dos borradas: *"Otsu baja la confianza"* y *"el upscale no
aporta"*. En ese entorno la misma imagen sin tocar se lee a 0,77 en vez de
0,93, y el upscale la lleva a 0,93.

Sobrecorregí: **restauré los tres pasos** y reescribí el ADR diciendo que
la primera conclusión era la equivocada.

### Intento 3: medí paso por paso en los dos entornos

Que es lo que debí haber hecho de entrada. Con la tabla completa delante
—la de arriba— se ve que ninguna de las dos conclusiones anteriores era
correcta:

- el upscale **sí** paga, en producción, y mi primer intento lo descartó
  por medir en el lugar equivocado;
- binarizar **sí** daña, en los dos entornos, y mi segundo intento lo
  restauró por sobrecorregir desde un mensaje de error en vez de desde la
  medición completa.

La primera versión tenía razón en Otsu y se equivocó en el upscale. La
segunda, al revés. La tercera es la tabla.

## La parte que vale guardar

Es **la cuarta vez** que este proyecto generaliza desde una sola
configuración:

| | |
|---|---|
| [ADR 009](009-deteccion-del-qr-en-el-cliente.md) | Midió la detección del QR con **un** decodificador, ZXing, y concluyó que al servidor no le quedaba nada por hacer |
| [ADR 010](010-dos-motores-para-leer-el-qr.md) | Probó otros tres sobre las mismas fotos: el de WeChat leyó 4 de 6 donde ZXing leía 1. La premisa del 009 se cayó |
| [ADR 010](010-dos-motores-para-leer-el-qr.md) | Y probó catorce variantes de preprocesado, ninguna de las cuales rescató una foto que cambiar el decodificador no arreglara igual |
| **Este** | Midió el preprocesado del OCR con **un** idioma en **un** sistema operativo, concluyó al revés de lo que pasa en producción, y después sobrecorrigió |

Las tres primeras se cometieron y se corrigieron con otra medición. La
cuarta se cometió **mientras se escribía el ADR que llamaba a eso un
patrón**, lo cual dice bastante sobre lo poco que sirve saberlo.

**Lo que sirvió no fue más cuidado: fue un segundo entorno corriendo los
números solo.** Esa es la lección operativa, y es la razón de que el paso
de CI que instala Tesseract instale `tesseract-ocr-spa` y no solo el
paquete base. Si midiera con `eng` —que es lo que pasa si
`TESSDATA_PREFIX` apunta mal— los tests pasarían igual y la medición
vendría de un sistema distinto al que corre. Hay un paso aparte que
verifica que `spa` quedó disponible, porque **medir con español y medir
con inglés pasan los dos**.

La segunda lección es más chica y más vergonzosa: cuando CI contradijo la
medición, sobrecorregí desde el *mensaje de falla* en vez de volver a
correr la medición completa en el entorno nuevo. Dos minutos de container
habrían evitado el segundo intento.

## Consecuencias

**Los tests ya no afirman direcciones.** La primera versión afirmaba *que
Otsu bajaba la confianza* y *que el upscale no aportaba*. Las dos valían
en una máquina y eran falsas en la otra. Ahora los tests afirman solo lo
que vale en los dos lados —que la página se sigue leyendo, que una página
buena no se arruina, y las reglas estructurales— e **imprimen** los
números sin afirmar su dirección:

```
--- aporte de cada paso, idioma spa ---
   420 px  sin tocar         cuit=true  conf=0.77
   420 px  solo upscale      cuit=true  conf=0.93
   420 px  solo binarise     cuit=true  conf=0.81
   420 px  upscale+binarise  cuit=true  conf=0.70
   420 px  prepare (up+des)  cuit=true  conf=0.93
```

Eso no es aflojar la vara, es moverla al lugar correcto: un log de build
que lleva la medición vale más que una aserción que pasa donde fue
escrita. Y la tabla incluye `upscale+binarise`, que es la combinación que
**no** se usa — el número que justifica dejarla afuera tiene que estar a
la vista, o el próximo la agrega de nuevo.

**`binarise` sigue existiendo como método**, fuera de `prepare`. No es
código muerto con un comentario lindo: es lo que el test mide para
imprimir la fila que lo descarta.

**El idioma es `spa` donde está instalado y `eng` si no, y la lectura dice
cuál fue.** Eso ya estaba, y ahora se entiende para qué: sin ese dato las
dos tablas de arriba serían una sola tabla contradictoria.

**Un `TESSDATA_PREFIX` mal puesto degrada, no mata.** Si apunta a un
directorio que no sirve, el motor cae al `eng` del jar en vez de quedarse
sin OCR. Correcto en producción, y un riesgo en CI — que es por qué CI lo
verifica aparte.

**La confianza que se guarda es la que midió Tesseract, no el 0,5 de
base.** El javadoc de `FieldSource.OCR` lo prometía desde el primer commit
— *"OCR adjusts it afterwards with what the engine reports"* — y hasta
ahora nada lo cumplía. Un 0,5 constante en cada campo de OCR, sea un
escaneo limpio o una foto oscura, haría de `field_confidence` una
constante, y una constante no es una señal.

**`invoice.ocr.confidence` es un summary y no un gauge.** Un gauge guarda
el último valor y el último comprobante no es la pregunta; lo que se
quiere saber es cómo se ve la distribución. Solo se registra cuando el OCR
corrió: anotar un cero por cada comprobante cuyo QR decodificó enterraría
las lecturas reales bajo una pila de ceros de documentos que el OCR nunca
tocó. Y es **la opinión de Tesseract sobre su propia salida**, no la
probabilidad de que el texto esté bien — vale ser preciso, porque el
número va a un dashboard donde alguien lo va a leer como exactitud.

## Lo que apareció de paso

**Las anomalías no las leía nadie.** Cada estrategia viene armando una
lista de notas desde la Fase 1 — *"el PDF tiene texto pero no se pudo
identificar una razón social"* — y al final del pipeline se descartaban.
Lo encontré porque el OCR agregó una nota ahí y no apareció en ninguna
parte. Ahora se loguean con el `jobId`. La que más vale es la segunda: es
el dataset que dice qué layouts no cubren las reglas de parseo, y se
estaba tirando.

**El formato de un número dependía del locale.** La nota salía
`ocr confidence 0,92` con coma en una máquina en español y con punto en
CI. `String.format` con `Locale.ROOT`, y lo mismo en los `printf` de los
tests — comparar un log de CI con uno local es justo lo que hubo que
hacer acá, y con comas y puntos mezclados es más difícil.

**Y un bug de zona horaria en el historial**, que no es del OCR pero lo
encontró la misma corrida a las 23:45. `GET /jobs` convertía
`LocalDate.now()` —fecha local— con `ZoneOffset.UTC`: a esa hora en
Argentina ya es el día siguiente en UTC, así que un comprobante subido
"hoy" caía fuera del rango de "hoy" y **el historial se vaciaba todas las
noches entre las nueve y medianoche**, volviendo solo a la mañana. La zona
es ahora `America/Argentina/Buenos_Aires`, fija y no
`ZoneId.systemDefault()`: un contenedor corre en UTC salvo que alguien
ponga `TZ`, y el default estaría bien en la laptop y mal en producción.

Tres bugs del mismo tipo en un día, contando el idioma del OCR: **un
default del entorno —idioma, locale, zona— metiéndose en una decisión del
dominio.** No es casualidad que aparezcan juntos; es el mismo descuido con
tres caras.

## Alternativas descartadas

**Solo deskew**, que fue la decisión durante unas horas. La descarta el
dato: en el entorno que corre, el upscale aporta dieciséis puntos sobre
una página chica.

**Los tres pasos**, que fue la decisión durante otra media hora. La
descarta la misma tabla: binarizar pierde en los dos entornos, y
combinado con el upscale es lo peor de las dos columnas.

**Hacer el pipeline condicional por idioma o por sistema operativo.**
Resolvería las dos tablas a la vez y crearía algo peor: un preprocesado
que se comporta distinto según la máquina, o sea un bug que no se
reproduce.

**Umbral adaptativo en vez de global** (`adaptiveThreshold`). Más cerca de
lo que Tesseract hace internamente, y por eso mismo sospechoso: estaría
haciendo peor lo que Tesseract ya hace mejor. Pero después de todo esto la
honestidad obliga a decir que ese razonamiento es exactamente el tipo de
argumento que ya falló dos veces hoy, así que si alguna vez se prueba, se
prueba midiendo y en los dos entornos.

**Deskew por líneas de Hough** en vez de por el rectángulo de área
mínima. Más preciso cuando hay reglas rectas impresas, y un ticket muchas
veces no tiene ninguna.

**Empaquetar `spa.traineddata` en el repositorio.** Quince megas de
binario en git para evitar una línea de Dockerfile, y haría que el modelo
del idioma se versione a mano en vez de venir de la distribución.

**Servicio de OCR aparte, en vez de JNI.** Ya descartada en el
[ADR 002](002-tesseract-y-no-paddleocr.md).
