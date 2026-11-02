# 010 — Dos motores para leer el QR, y OpenCV para las fotos

**Estado:** aceptada · implementada
**Fecha:** 2026-11-01
**Revisa:** [ADR 009](009-deteccion-del-qr-en-el-cliente.md) — le corrige la premisa
**Relacionada:** [ADR 004](004-qr-primero-ocr-de-respaldo.md)

## Contexto

El [ADR 009](009-deteccion-del-qr-en-el-cliente.md) se escribió ayer y
concluyó que el servidor no podía hacer más: que las fotos no traen la
información y que el único lugar donde queda algo por hacer es el
navegador. Lo escribió con una medición real y honesta —ocho fotos, cero
campos— pero con **un solo decodificador**, ZXing, y sin haber probado
ninguno otro.

La frase que cerró esa puerta fue:

> cuando el módulo del QR mide menos de dos píxeles en la foto, no hay
> algoritmo que invente los que faltan.

Es cierta como física y está mal usada. Que no se puedan inventar
píxeles no implica que ZXing esté sacando todo lo que hay en los que sí
están. Eran dos afirmaciones distintas y las traté como una sola.

Se midió. Mismas seis fotos de la tabla del 009 —las tres que quedaban
accesibles, en 1200×1600, ya redimensionadas en el camino, o sea peores
que el original de la cámara— contra cuatro detectores.

| Comprobante | ZXing | OpenCV `QRCodeDetector` | `QRCodeDetectorAruco` | **WeChat** |
|---|---|---|---|---|
| A, página | ❌ | ❌ | ❌ | ❌ |
| A, primer plano | ❌ | ❌ | ❌ | ❌ |
| B, página | ❌ | ❌ | ❌ | ⚠️ payload vacío |
| B, primer plano | ⚠️ payload vacío | ❌ | ❌ | ⚠️ payload vacío |
| C, página | ❌ | ❌ | ❌ | ✅ **13 campos** |
| C, primer plano | ❌ | ✅ | ✅ (a 2×) | ✅ **13 campos** |
| **total** | **1 de 6** | 1 de 6 | 1 de 6 | **4 de 6** |

**ZXing leyó uno, y es el que trae el payload vacío: cero datos
utilizables. WeChat leyó cuatro, dos de ellos con el payload ARCA
completo.** En el primer plano de C, hasta el detector básico de OpenCV
lo lee a escala 1× mientras ZXing, con `TRY_HARDER` y los dos
binarizadores, no encuentra ni el código.

Tres cosas que la medición dejó claras:

**La afirmación de los dos píxeles es falsa.** La foto de página de C
decodificó a **1,87 píxeles por módulo**. El test
`readsACodeAtOnePixelPerModuleWhereZxingCannot` lo lleva más lejos y fija
el caso a **1,00 px/módulo** sobre un código sintético de 69 módulos: ahí
ZXing no encuentra nada y WeChat lo lee exacto.

**El parser nunca estuvo roto.** El payload recuperado de C pasó por
nuestro `ArcaQrDecoder` sin una queja: versión 1, los trece campos, el
dígito verificador del CUIT válido, y el tipo de comprobante, el punto de
venta, el número y la fecha coincidiendo con lo impreso en el papel. (Los
valores no van acá: son de una factura real de un tercero y este repo es
público.) El eslabón flojo era solo el lector de imagen. (De paso: ese payload escribe `tipoCodAut` **bien**, así
que la decisión del ADR 004 de aceptar las dos grafías ahora tiene
evidencia de los dos lados.)

**Las dos que no lee nadie no son un problema de captura.** El primer
plano de A tiene el código grande y nítido. Lo que tiene son módulos que
se chorrean unos sobre otros y bandas blancas horizontales: una impresora
gastada. Catorce variantes de preprocesado, ningún detector, nada. Entra
en la misma categoría que el payload vacío de B — el emisor imprime mal,
y eso no lo arregla ni el cliente ni el servidor.

La muestra sigue siendo chica: seis fotos, tres emisores. Alcanza para
descartar una conclusión, no para instalar un número.

## Decisión

**El lector de QR pasa a tener dos motores, y se agrega el detector que
WeChat aportó a OpenCV.**

`QrImageReader` queda como fachada con la misma API y los motores detrás,
probados en orden:

1. `ZxingQrEngine` — Java puro, sin nativo. Resuelve el PDF rasterizado y
   el escaneo limpio, que es donde siempre funcionó.
2. `WeChatQrEngine` — OpenCV. Es el que lee fotos.

**El orden es una decisión de latencia, no de calidad.** ZXing va primero
porque cuesta milisegundos y así un documento limpio nunca toca la
librería nativa. Sobre la muestra medida, todo lo que leyó ZXing también
lo leyó WeChat, así que invertir el orden cambiaría cuánto tarda una foto
y nada de lo que se lee.

Detalles que la implementación fija, cada uno con su test:

- **OpenCV viene de `org.bytedeco`, no de `org.openpnp`.** El build de
  openpnp es solo el core y no trae `opencv_contrib`, que es donde vive
  `wechat_qrcode`. Se verificó mirando las clases de los dos jars.
- **Se usa la API de JavaCPP y no la de `org.opencv.*`.** Esta es la
  decisión menos obvia del ADR y costó un CI roto. Bytedeco expone los
  dos namespaces, y `org.opencv.*` es el familiar — el mismo que usaría
  cualquiera que ya haya tocado OpenCV en Java. El problema es cómo se
  sirve: `org.opencv.*` lo atiende **una sola librería nativa monolítica**
  que abarca todos los módulos de OpenCV, `highgui` incluido. Y `highgui`
  quiere GTK y X11. En Windows cargó sin chistar; en el runner de Linux
  reventó con `no jniopencv_highgui in java.library.path`, porque un
  servidor headless no tiene toolkit gráfico. Cargando
  `opencv_wechat_qrcode` de JavaCPP entra solo lo que este motor
  necesita: wechat_qrcode, dnn, imgproc, core. Instalar GTK en una imagen
  de servidor para contentar a un módulo que nadie llama habría sido
  arreglarlo por el lado equivocado.
- **Sin archivos de modelo.** `WeChatQRCode` acepta opcionalmente dos
  modelos Caffe —un detector CNN y un paso de superresolución—. El 4 de 6
  se midió con el constructor sin argumentos, o sea sin ninguno de los
  dos. Agregarlos es un experimento posterior, no un requisito.
- **Los binarios se eligen por plataforma, y no de manera simétrica.**
  `linux-x86_64` va **incondicional**; `windows-x86_64` cuelga de un
  perfil activado por SO. No se incluyen todos porque terminarían dentro
  del jar ejecutable y la imagen de producción se llevaría los `.dll` de
  Windows. Y Linux no cuelga de un perfil porque la primera versión de
  esto sí lo hacía y el CI se construyó sin binario: OpenCV no cargó, las
  fotos dejaron de leerse y lo único que lo delató fue el test que
  compara contra la plataforma. Que la imagen de producción tenga su
  binario no puede depender de que un `<activation>` se dispare.
- **Un detector por llamada, no un campo.** El objeto nativo guarda
  estado y esto cuelga de un singleton detrás de un handler web.
  `ZxingQrEngine` se cambió igual, lo que además elimina el `reset()`
  que había que acordarse de llamar.
- **La imagen se copia siempre a una matriz de un canal.** Un
  `BufferedImage` ajeno puede tener el raster con padding o con offset, y
  leer su arreglo de atrás sin copiar cortaría la imagen en diagonal: un
  bug que se ve como un decodificador roto. Hay un test por cada tipo de
  imagen que el pipeline puede entregar.
- **Al texto decodificado se le recorta un NUL.** OpenCV devuelve el
  resultado como un `std::string` cuyo tamaño cuenta su propio
  terminador, así que un payload de 309 caracteres llega con 310 y no
  coincide con nada. Ni `getString` ni `getStringBytes` lo limpian. Se
  recorta a mano, con el motivo escrito al lado: el síntoma es que el
  valor obtenido y el esperado **se imprimen idénticos** y el assert
  falla igual, que sin un test es media hora desagradable.

## Consecuencias

**La cobertura sobre la muestra pasa de 1 de 6 a 4 de 6**, y de 0 a 2 si
se cuentan solo las lecturas con datos aprovechables. Las dos que quedan
afuera son el mismo emisor imprimiendo mal.

**La imagen adelgaza.** El jar que produce la imagen —compilado en
Linux, con los binarios de una sola plataforma— baja de **208 MB a
~149 MB**, y la porción de OpenCV de **~104 MB a ~46 MB**. Suena al revés
de lo esperado al agregar una dependencia nativa, y la razón es que
openpnp empaqueta los binarios de **todas** las plataformas en un único
jar sin forma de pedir una sola.

El precio lo paga la máquina de desarrollo, no la imagen: compilado en
Windows el jar da 210 MB, porque lleva los binarios de Linux —que van
incondicionales— además de los propios. Son 61 MB que nunca se ejecutan
ahí. Se eligió así a conciencia: ver el punto de los binarios más
arriba.

**Al ADR 009 se le cae la premisa, no la decisión.** Detectar el QR en el
navegador sigue valiendo: el feedback inmediato convierte un fracaso que
se descubre después de subir en una corrección en el momento. Pero deja
de ser "la decisión que salva el producto" y pasa a ser **una mejora de
UX**. En concreto:

- ya no justifica mover trabajo de fase;
- la distinción `QR` (confianza 1.0) contra `QR_CLIENT` (0.95) **se
  mantiene**: un payload que el servidor no corroboró contra el píxel
  sigue siendo un dato que el servidor no vio;
- la frase de los dos píxeles queda marcada como refutada en el propio
  009.

**El criterio del "≥ 85%" no vuelve.** Sobre esta muestra da 67%, y seis
fotos de tres emisores no son base para escribir un porcentaje en un
criterio de aceptación. Ese fue exactamente el error original. Vuelve a
escribirse cuando haya una muestra que lo sostenga.

**El arranque del nativo es un problema de Windows, no de producción.**
`Loader.load` de JavaCPP tarda **~20 s en Windows, en cada arranque de la
JVM** — no es caché fría: se midió en caliente, y el log de debug muestra
el tiempo yéndose en openblas y una pila de DLLs del UCRT. Excluir
openblas no es salida: sin él OpenCV no carga.

En Linux, que es lo que corre en producción, **la clase entera de tests
que dispara la carga tarda 2,17 s**, con los per-método sumando ~1,4 s.
O sea menos de un segundo de carga, contra veinte. **No se precalienta
nada**, que era la pregunta abierta. Y se deja escrito el número para que
nadie lo vuelva a estimar: la molestia es real al correr los tests en
Windows y no significa nada sobre el servidor.

**`WeChatQrEngine.available()` tiene que llegar a las métricas.** Si el
nativo no carga, no se rompe nada ni se lanza nada: las fotos
simplemente dejan de leerse y la cobertura vuelve en silencio a lo que
ZXing puede solo. Por ahora lo cubre el test
`reportsWhetherItCanRunAtAll`, que compara contra la plataforma en vez de
saltearse, para que CI —que corre en linux-x86_64— falle fuerte si el jar
nativo deja de llegar al classpath. Cuando se cablee la observabilidad
(semana 6), el estado del motor va como tag junto a
`invoice.qr.detection.rate`.

## Alternativas descartadas

**Quedarnos con openpnp y usar el `QRCodeDetector` del core.** Era gratis
—ya estaba en el pom, aunque ninguna clase lo usaba todavía— y habría
evitado cambiar de artefacto. Lee 1 de 6, igual que ZXing. No alcanza
para justificar nada.

**Reemplazar ZXing por WeChat a secas, en vez de tener dos.** Tentador
por lo simple. Pero ZXing es Java puro y resuelve el PDF rasterizado sin
tocar el nativo, que es el caso que hoy funciona al 100%. Tener dos
motores cuesta una interfaz y una lista; sacar ZXing cuesta hacer que el
único caso que anda dependa de una librería nativa.

**`opencv-platform` acotado con la propiedad `javacpp.platform`.** Es lo
que documenta bytedeco y son tres líneas de pom en vez de treinta. Se
probó: heredada desde el pom padre **no acota nada**, `dependency:tree`
seguía resolviendo las diez plataformas. Pedir el classifier a mano es
más largo de leer pero no depende de que se active un perfil ajeno.

**Cargar los modelos CNN de WeChat.** Son dos archivos Caffe y prometen
más alcance todavía. No se agregan porque el 4 de 6 se consiguió sin
ellos: primero el cambio que ya está medido, después el experimento.

**Dejar el ADR 009 como estaba y escribir solo el motor nuevo.** Es lo
cómodo. Pero el 009 está publicado en un repo público afirmando como
hecho del dominio algo que la medición contradice, y el 009 existe
justamente porque el 004 fue corregido con este mismo criterio.
