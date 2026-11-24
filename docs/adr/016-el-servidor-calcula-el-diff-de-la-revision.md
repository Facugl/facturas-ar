# 016 — El servidor calcula el diff de la revisión

**Estado:** aceptada · implementada
**Fecha:** 2026-11-24

## Contexto

`PUT /jobs/{id}/confirm` no aceptaba cuerpo. Decía una sola cosa —*miré
esto y está bien*— y eso era honesto mientras no existiera el formulario
de revisión: diseñar el contrato antes de la pantalla es adivinar qué
manda la pantalla.

Pero dejaba dos agujeros. Uno es que una extracción equivocada se podía
confirmar y exportar equivocada. El otro es peor: **`field_corrections`
llevaba cero filas desde la primera migración.** Esa tabla es una apuesta
del día 1, y la apuesta era que el dataset que dice *qué regla de parseo
anda peor* no se puede juntar después, porque para entonces las
correcciones ya se tiraron. Una tabla vacía no es una apuesta cumplida.

El criterio de aceptación 4 dice: *el usuario puede corregir cualquier
campo y confirmar; cada corrección queda registrada.*

## Decisión

El cuerpo es **opcional**, y lo que el cliente manda son **valores, no
cambios**.

```json
{
  "fields": {
    "issuerBusinessName": "PANADERÍA LA ESPIGA S.A.",
    "total": "18450.00",
    "cae": null
  }
}
```

El cliente dice "el total es 18450.00". **No** dice "cambié el total de
18000.00 a 18450.00". Quién decide qué cuenta como corrección es el
servidor, comparando contra la base.

### Por qué eso y no al revés

**La fila de auditoría no se puede falsificar.** `original_value` se lee
de la base acá, así que un cliente con un bug —o malintencionado— no
puede escribir la historia de una corrección que nunca pasó. Si el
cliente declarara el valor viejo, la tabla sería un registro de lo que el
cliente dice que pasó, que no es lo mismo que un registro de lo que pasó.

**Nada se puede olvidar.** Un formulario que manda los nueve campos en
cada submit —que es lo fácil de programar— produce exactamente las
correcciones que difieren, sin que el formulario colabore en nada.

El corolario es que confirmar sin cambiar nada no escribe ninguna fila, y
eso es lo que hace que `field_corrections` sea un dataset sobre la
extracción y no sobre cómo manda el formulario.

### Un mapa, y no un record con nueve campos nullables

La forma obvia para "el comprobante corregido" es un record con un campo
nullable por columna. No funciona, y el motivo es que `null` tendría que
significar dos cosas: *dejá esto como está* y *vaciá esto*. No hay forma
de distinguirlas, así que una de las dos se vuelve imposible — en la
práctica, vaciar un campo que la extracción puso mal, que es
exactamente para lo que existe un formulario de revisión.

Con un mapa, **cada clave presente es una afirmación sobre ese campo**:

| | |
|---|---|
| clave ausente | dejalo como está |
| clave con valor | ese es el valor |
| clave con `null` o `""` | vacialo |

### Los valores viajan como strings

Porque `field_corrections` guarda `TEXT` —la fila de auditoría se queda
con el string igual— y porque obliga a que cada valor pase por **un
parseo por campo**, que es donde va la validación. Un CUIT con el dígito
verificador mal se rechaza con el mismo constructor de `Cuit` que usa la
extracción, en vez de entrar a la base porque llegó como número.

### Se parsea todo antes de escribir nada

Dos pasadas: primero se parsean los nueve valores, después se asignan.
**Esto no es prolijidad.** Con parseo y asignación en un solo paso, un
formulario cuyo cuarto campo es inválido ya tiene los tres primeros
escritos, y el cliente recibe un 400 diciendo que el pedido falló
mientras parte del pedido entró.

Lo encontró un test que yo había escrito para verificar un comentario que
yo mismo había puesto —"se valida todo antes de escribir nada"— y que era
falso cuando lo escribí.

### Corregir también reescribe `field_sources`

Un campo corregido pasa a `USER` en el mapa de fuentes, con confianza
1.0. No es cosmético: ese mapa es lo que pinta el cartelito *"esto vino
del QR, confiá"* en el formulario. Dejarlo igual significaría que un
campo que el usuario escribió a mano sigue diciendo que es dato oficial
de ARCA — el formulario mentiría sobre lo único que existe para decir con
honestidad.

`FieldSource.USER` estaba en el enum desde el primer commit, documentado
como *"beats any extraction"*, y hasta ahora nada lo escribía nunca. Es
la segunda apuesta del día 1 que esta fase cobra.

### Una sola llamada, no dos

Corregir y confirmar es una operación. Una corrección sin confirmación
sería un borrador, y este producto no tiene borradores: el formulario se
abre, se edita y se manda una vez. Partirlo en `PATCH` y después `PUT`
inventaría un estado —"corregido pero sin confirmar"— que no le hace
falta a nadie y que la máquina de estados del `Job` no tiene.

### Solo se corrige lo que el cliente vio

Los nueve campos corregibles son exactamente los que devuelve
`JobResponse.Fields`. Aceptar una corrección a un campo que la respuesta
nunca expuso sería aceptar un valor que el usuario no pudo leer, revisar
ni estar en desacuerdo con. Los cuatro del QR que quedan afuera
—`exchangeRate`, `recipientDocType`, `recipientDocNumber`,
`authCodeType`— y los tres de v2 no son corregibles por eso, y el arreglo
es mostrarlos primero, no aceptarlos acá.

Hay un test que fija esa lista contra la respuesta, así que agregar un
campo a una sin la otra es un test que falla y no una inconsistencia
silenciosa que descubre un usuario.

## Consecuencias

**Un campo desconocido se rechaza nombrándolo**, con la lista de los
válidos, en vez de ignorarse. Un cliente que manda `issuerBussinessName`
recibiría si no un 200 y vería que su corrección no está, que es el tipo
de bug que se le echa a la base de datos.

**Un monto se normaliza a dos decimales: se completa, nunca se redondea.**
Las dos mitades importan. Completar es lo que mantiene un no-cambio como
un no-cambio: la columna es `NUMERIC(15,2)`, así que `18450.0` y
`18450.00` son un solo valor guardado, y sin normalizar acá un formulario
que reformatea lo que recibió registraría una corrección en cada monto
que devuelve. Rechazar un tercer decimal en vez de redondearlo es la otra
mitad: convertir `10.005` en `10.01` en silencio es un centavo que nadie
le preguntó a nadie, en un archivo que va a un contador.

**Se arregló un bug que ya existía: confirmar no invalidaba el cache.**
`GET /jobs/{id}` cachea 30 segundos
([014](014-el-cache-se-invalida-y-la-clave-lleva-el-dueno.md)), y
confirmar cambia la fila cacheada. Antes el síntoma era chico —el estado
tardaba en actualizarse— y ahora sería el peor posible: el usuario
corrige un campo, la API le contesta 200 con el valor nuevo, y el próximo
poll le devuelve el viejo. Nada roto en la base, que es lo que lo hace
tan confuso desde afuera.

**Los mapas JSON se reemplazan, no se mutan.** Hibernate solo escribe una
columna `jsonb` si la referencia que tiene cambió. Mutar el mapa en su
lugar es la versión de esto que pasa todos los tests unitarios y no
persiste nada; hay un test contra Postgres específicamente para eso,
porque es la única forma de verlo.

**`InvoiceField` se mudó de `extraction` a `shared-domain`.** El
`upload-api-service` no depende de `extraction` y no debería: ese enum
nombra los campos de un comprobante, no los pasos de un algoritmo. El
mismo argumento que ya tenía puesto a `FieldSource` ahí. Dos cosas hablan
ese vocabulario —la cascada que llena los campos y el formulario que los
corrige— y solo una es extracción.

## Medido contra el stack real

Con los ocho contenedores arriba, y a propósito sobre **un comprobante
del que la extracción no sacó nada** — que es el caso del criterio 3 y
donde la revisión importa de verdad:

| | |
|---|---|
| Subo un PDF sin QR | `EXTRACTED`, `qrDetected: false`, `fields: {"sources":{}}` |
| Confirmo mandando ocho campos | **200**, y la respuesta trae los ocho con `source: USER` |
| `field_corrections` | pasa de **0 filas en todo el proyecto** a **8**, con `original_value` en `NULL` y el valor nuevo en `corrected_value` |
| `/actuator/prometheus` | `invoice_field_correction_rate_total` con una serie por campo |
| El export del período | una línea CSV válida de un comprobante del que la cascada no leyó nada: `02/03/2026;6;Factura B;3;1201;30-71234567-1;PANADERIA LA ESPIGA S.A.;PES;;18450,00;` |
| Reconfirmar | **409**. `CONFIRMED` es terminal |
| CUIT `30-71234567-9` | **400** — *"not a valid CUIT — check the verifier digit"* |
| Campo `issuerBussinessName` | **400**, con los nueve nombres válidos en el mensaje |
| Fecha `02/03/2026` | **400** — *"expected a date as yyyy-MM-dd"* |
| Monto `10.005` | **400** — *"an amount has at most two decimals"* |
| Después de los cuatro rechazos | el job sigue en `EXTRACTED`: nada entró |

La fila del export es la que cierra el arco. Un comprobante que la
cascada no pudo leer termina igual en el archivo que el producto existe
para producir, y el criterio 4 dejó de ser una tabla vacía.

## Lo que apareció de paso, y era peor

Probando el `curl` del README —el del cuerpo multilínea, el que iba a
quedar documentado— salió un **500**. La causa inmediata era mía y del
terminal: Git Bash en Windows mandó la `Í` de "PANADERÍA" en Latin-1 y el
cuerpo no era UTF-8 válido. Pero la respuesta debería haber sido un 400,
no un 500, así que seguí tirando del hilo.

**Todo pedido mal formado de toda la API contestaba 500:**

| | antes | ahora |
|---|---|---|
| `POST /auth/register` con `"no-es-un-email"` | 500 | **400** |
| …con una clave de 5 caracteres | 500 | **400** |
| …con un cuerpo que no es JSON | 500 | **400** |
| `GET /jobs/no-es-un-uuid` | 500 | **400** |
| `GET /export/csv` sin parámetros | 500 | **400** |
| `GET /export/csv?from=marzo` | 500 | **400** |
| `DELETE` sobre cualquier cosa | 500 | **405** |

La causa es que `ApiExceptionHandler` declaraba
`@ExceptionHandler(Exception.class)` como red de último recurso, y eso le
gana a las excepciones propias de Spring MVC: el mapeo por defecto de
`MethodArgumentNotValidException` a 400 ocurre más afuera, en el
`DispatcherServlet`, y un `@RestControllerAdvice` que reclama `Exception`
llega primero. El arreglo es extender
`ResponseEntityExceptionHandler`, que vuelve a poner los handlers
específicos — y le ganan al catch-all porque Spring elige el más
específico.

Dos cosas que vale anotar:

**Las anotaciones `@Email` y `@Size` que escribí en la fase anterior eran
decorativas.** Estaban puestas, nunca se aplicaron, y nada falló para
avisarlo: el pedido se rechazaba, pero con el código equivocado y un
mensaje que no decía nada. Una validación que contesta 500 es peor que no
tener validación, porque le dice al cliente que la culpa es nuestra y que
reintentar puede servir, cuando el pedido estaba mal y va a seguir mal.

**Extender esa clase tiene un filo.** `ResponseEntityExceptionHandler` ya
maneja `MaxUploadSizeExceededException`, así que mi handler para esa
excepción quedó como *ambiguous mapping* y el contexto entero dejó de
arrancar. Todo handler propio para una excepción que Spring MVC también
conoce tiene que pasar a ser un `@Override`.

Lo encontró probar la API a mano con `curl` después de construir algo que
no tenía nada que ver, que es el argumento para hacer eso.

## Alternativas descartadas

**El cliente declara qué cambió.** `[{"field":"total","from":"18000.00",
"to":"18450.00"}]`. Mapea 1 a 1 con la fila de auditoría, lo cual es
justo el problema: la fila pasaría a ser lo que el cliente dice que pasó.
Y obliga al formulario a llevar la cuenta de los cambios, que es trabajo
que el servidor puede hacer mejor porque tiene el valor viejo.

**Un `PATCH /jobs/{id}/extracted-data` aparte, y después confirmar.** Más
REST. Inventa el estado "corregido pero sin confirmar", agrega una
llamada que puede quedar a mitad de camino, y no resuelve nada que el
formulario necesite.

**Valores tipados en el JSON** (`"total": 18450.00` en vez de
`"18450.00"`). Más natural de leer. A cambio, el parseo lo hace Jackson
antes de que el código lo vea: un total con tres decimales llega como
`BigDecimal` y ya es tarde para rechazarlo con un mensaje que diga por
qué, y un CUIT llega como número sin pasar por `Cuit`. Y la fila de
auditoría necesita el string igual, así que la conversión pasa en algún
lado — mejor en un lugar donde se puede validar.

**Aceptar los dieciséis campos del esquema.** Más completo en el papel.
Invita al usuario a llenar `netAmount` y `vatAmount`, que son de v2 y que
el export ignora: datos que se escriben y no se leen, que es peor que no
ofrecer el campo.

**Rechazar correcciones a los campos del QR, porque el QR es exacto.**
Tentador y equivocado. El QR es exacto *cuando decodificó*; el criterio 4
dice "cualquier campo" y el 3 dice que un comprobante sin QR tiene que
poder completarse a mano. Que los campos del QR sean de solo lectura **por
defecto** es una decisión del formulario, no del endpoint: la pantalla
pide un clic extra para editarlos, y el servidor los acepta igual.
