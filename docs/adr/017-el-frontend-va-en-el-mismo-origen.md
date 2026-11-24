# 017 — El frontend va en el mismo origen que la API

**Estado:** aceptada · implementada
**Fecha:** 2026-11-24
**Relacionada:** [ADR 015](015-sesion-con-cookie-y-no-un-token.md) · [ADR 009](009-deteccion-del-qr-en-el-cliente.md)

## Contexto

Todo lo construido hasta acá se demostraba con `curl`, `psql` y PromQL.
El stack del frontend estaba decidido desde el README §10 —React + Vite,
React Query, sin Redux— así que lo que quedaba por decidir no era con qué
sino **cómo se sirve**, y de ahí salieron tres o cuatro decisiones que
cuestan pensar.

## Decisión

### 1. El navegador ve un solo origen. Esta es la decisión importante.

El frontend no le habla a la API por otro origen: **la proxea**. En
desarrollo, con el `server.proxy` de Vite; en compose, con un nginx que
sirve el bundle y pasa `/auth`, `/documents`, `/jobs`, `/export` y
`/actuator` a la API.

La causa es la cookie del [ADR 015](015-sesion-con-cookie-y-no-un-token.md).
Es `HttpOnly` y `SameSite=Lax`, y **un navegador no manda una cookie
`Lax` entre orígenes distintos** — esa es justamente la regla que la
hace valer. La única forma de evitarlo es `SameSite=None; Secure`, que
exige HTTPS y entonces no funciona en `http://localhost` en absoluto.

Las alternativas eran debilitar la cookie, o dejar las cookies y pasar a
un token — o sea, deshacer el ADR 015 para acomodar al servidor de
desarrollo. Proxear cuesta un bloque de configuración en cada lado, y
tiene una ventaja que no es menor: **lo que se desarrolla es lo que se
despliega.** El CSRF, la sesión y el `SameSite` se comportan igual en las
dos.

Las rutas de la API se listan en vez de montarla bajo `/api`, porque esas
rutas ya están documentadas, ejercitadas con `curl` en el README y
afirmadas por los tests. Moverlas para comodidad del frontend sería el
frontend decidiendo el contrato de la API.

### 2. El obturador espera al QR — pero solo en la cámara

Acá había una tensión real entre dos criterios de aceptación, y resolverla
es una decisión y no un detalle:

- el **criterio 2** dice que la foto se acepta solo si el QR decodificó;
- el **criterio 3** dice que un comprobante **sin** QR tiene que entrar
  igual y poder completarse a mano.

Se resuelve así: **la cámara exige el QR, el selector de archivos no.**
Cuando alguien está sosteniendo un teléfono sobre un papel, decirle que
el encuadre no sirve es la única razón de estar ahí — podría corregir el
ángulo ahora. Un PDF sin QR no tiene ángulo que corregir, y desde el
[ADR 016](016-el-servidor-calcula-el-diff-de-la-revision.md) hay un
formulario que lo completa.

Y aun en la cámara hay una salida: *"sacar la foto igual"*. Una puerta sin
escape convierte un QR borroso en un callejón, y el servidor lee mejor
que el navegador — con dos decodificadores, uno de ellos el de OpenCV
([ADR 010](010-dos-motores-para-leer-el-qr.md)).

### 3. Dos motores en el cliente, y se dice cuál contestó

`BarcodeDetector` donde exista —es nativo, y en Chrome sobre Android lo
respalda el mismo código de plataforma que usa la app de cámara— y `jsQR`
de respaldo en Safari y Firefox.

**Cuál de los dos leyó se reporta, no se esconde.** Este proyecto ya
aprendió eso caro: el [ADR 009](009-deteccion-del-qr-en-el-cliente.md)
concluyó que al servidor no le quedaba nada por hacer porque ZXing leía 1
de 6 fotos, y el [ADR 010](010-dos-motores-para-leer-el-qr.md) encontró
que el detector de OpenCV leía 4 de las mismas 6. Una medición que no dice
qué decodificador la produjo es una medición que puede estar equivocada
exactamente de esa forma.

### 4. Sin router, y con fecha de vencimiento

Dos pestañas y una vista de detalle. Un router compraría *deep links*
—poder recargar sobre un comprobante, o mandarle una URL a alguien— y
costaría una dependencia y una capa de indirección sobre tres piezas de
estado. A este tamaño gana el estado. En el momento en que haya una
pantalla que valga la pena linkear desde afuera, eso se da vuelta, y lo
que hay que agregar es `react-router`.

### 5. Dos rangos de fecha en una pantalla, a propósito

El historial filtra por **fecha de carga** y el export por **fecha de
emisión**, y la pantalla los rotula así. No es una inconsistencia que
haya que esconder: una factura de marzo cargada en mayo pertenece a marzo
en un archivo contable y a mayo en "qué hice hoy". Filtrar el historial
por fecha de emisión esconde justo lo que alguien abre un historial para
buscar — los jobs que fallaron, y los que no decodificaron el QR y por
eso no tienen fecha de emisión en absoluto.

Darles un solo rango compartido significaría que una de las dos preguntas
recibe la respuesta equivocada en silencio.

### 6. Los campos del QR son de solo lectura **por defecto**

Un valor del QR es correcto, así que lo normal es no invitar a editarlo:
una tecla de más en un CAE es peor que un clic de más. "Editar" lo
desbloquea.

Que sea *por defecto* es una decisión **del formulario, no del endpoint**:
la API acepta la corrección igual, y tiene que hacerlo, porque el criterio
3 pide poder completar a mano un comprobante sin QR.

### 7. El formulario manda los nueve campos siempre

Qué cuenta como corrección lo decide el servidor comparando contra la
base ([ADR 016](016-el-servidor-calcula-el-diff-de-la-revision.md)). Así
el formulario no lleva la cuenta de lo que se tocó y **no puede
equivocarse**; confirmar sin cambiar nada no escribe ninguna fila de
auditoría.

## Consecuencias

**Hay un endpoint nuevo: `GET /jobs`.** No existía — la API solo tenía
`GET /jobs/{id}` — así que el historial no se podía construir. Tres cosas
de esa consulta son decisiones:

| | |
|---|---|
| Una fila por **documento**, no por job | Un documento tiene muchos jobs, que es lo que permite reprocesar sin volver a subir. Sin la subconsulta, un comprobante que pasó por el reaper aparecería dos veces, y eso se lee como "se subió dos veces" |
| `LEFT JOIN` a los datos extraídos | Un job que falló no tiene fila ahí. Un join interno descartaría en silencio justo las filas que alguien abre un historial para encontrar |
| Una proyección, no entidades | La lista necesita nueve columnas y ningún comportamiento. Cargar entidades traería el documento, el usuario y —perezosamente, una consulta por fila— los datos extraídos, para renderizar un resumen |

Lo sirve `ix_documents_user_uploaded`, que está en el esquema desde la
primera migración para una consulta que no existió hasta hoy.

**No se cachea.** `GET /jobs/{id}` está en Redis porque el frontend
pregunta por un job cada segundo y medio mientras procesa. Esta es la
lectura opuesta: se abre de vez en cuando, sobre una lista que cambia en
cada subida y en cada confirmación. Cachearla significaría invalidar en
las dos, por usuario y por página, para ahorrar una consulta que nadie
hace en un bucle.

**El criterio 7 se volvió a testear sobre la consulta nueva**, y es donde
es más fácil olvidarlo: un endpoint de lista no tiene un id que chequear,
así que lo único que separa a dos usuarios es el `WHERE`.

**La cámara se apaga de verdad.** Los *tracks* del stream se detienen al
desmontar. Sin eso el teléfono sigue grabando con la luz prendida, que
desde afuera se ve exactamente igual que spyware.

**El bundle pesa 410 kB, 135 kB comprimido**, casi todo React, React
Query y jsQR. nginx lo sirve con gzip y los `assets` con hash van
inmutables; `index.html` nunca se cachea, o un deploy dejaría navegadores
apuntando a nombres de archivo que ya no existen.

**vitest 3 y no 4**, y el motivo está medido: la 4.1.12 no corre acá en
absoluto — un archivo con un solo `expect(1 + 1)` falla al recolectar con
`Cannot read properties of undefined (reading 'config')`, sin config, sin
setup, con `node_modules` recién instalado y con los dos pools. El costo
queda escrito en vez de escondido: `@vitest/mocker` arrastra dos avisos
sin arreglo en la línea 3.x y `npm audit` lo dice en cada instalación. Son
alcanzables por el endpoint HTTP del mocker, que es del modo browser y de
la UI de vitest, y ninguno de los dos se usa acá. `tinypool`, el que sí
era crítico, está pinneado a su 2.1.2 con un `overrides`.

**El frontend tiene 22 tests, y cubren dos cosas.** El *wrapper* de
`fetch`, porque el baile del CSRF es invisible cuando se rompe — toda
escritura contesta 403, o 401 en un endpoint público, y nada en la
pantalla dice por qué; esa confusión costó una sesión del lado de la API
y era una cookie que faltaba. Y el formulario de revisión: que los nueve
campos estén, que las insignias de origen se muestren, que el candado del
QR no se invierta, y que se manden los nueve. El resto de los componentes
los cubre el `tsc -b` del build, que es poco pero no es nada.

## Alternativas descartadas

**CORS, con la API permitiendo el origen del frontend.** Es lo primero
que se intenta y no funciona con esta cookie: `SameSite=Lax` no cruza
orígenes, y `None; Secure` necesita HTTPS. Para que funcione hay que
debilitar la cookie o cambiar a un token, o sea pagar en seguridad por una
comodidad de desarrollo.

**Montar la API bajo `/api`** con `server.servlet.context-path`. Haría el
proxy una sola línea. A cambio rompe todos los `curl` del README, los
ADRs que citan rutas y los tests. El frontend no debería mover el
contrato de la API para ahorrarse cinco líneas de nginx.

**Servir el bundle desde Spring Boot**, en `src/main/resources/static`.
Un solo origen sin proxy y un solo contenedor. Pero mete el build de Node
dentro del build de Maven, hace que cada cambio de CSS recompile un jar
de 149 MB, y pierde el *hot reload* de Vite, que es la mitad de por qué
se usa Vite.

**Thymeleaf server-side**, que el README ya tenía descartada por UX
mobile. Sigue descartada, y ahora con una razón más concreta: la
detección del QR en vivo necesita `getUserMedia` y un bucle de decodificado
en el cliente, que es una SPA con otro nombre.

**Un router desde el principio.** Ver el punto 4: no es un "no", es un
"todavía no", y la condición está escrita.

**No cachear nada en el cliente y pedir siempre.** React Query está
justamente para el polling del estado, que es el caso de uso que el README
nombró al elegirlo. Sin cache, cada cambio de pestaña vuelve a pedir el
historial entero.
