# 014 — El cache se invalida, no se actualiza; y la clave lleva el dueño

**Estado:** aceptada · implementada
**Fecha:** 2026-11-23

## Contexto

`GET /jobs/{id}` es el read que el frontend machaca: mientras un
comprobante se procesa, el cliente pregunta por el mismo job cada uno o
dos segundos, y casi toda respuesta es igual a la anterior. Es el único
endpoint del sistema que vale la pena cachear.

**Entró último a propósito, después de separar el worker.** Cachear un
read no tiene nada interesante. Lo interesante es que la API sirve la
respuesta cacheada y **el worker es el que la hace incorrecta**, porque
el worker es el que termina el job. Con los dos en el mismo proceso la
invalidación habría sido un `Map.remove` y no habría demostrado nada.

## Decisión

Cache-aside en Redis, con cuatro reglas.

### 1. Se borra, no se reescribe

El worker hace `DEL`, no `SET` con el valor nuevo.

Reescribir significaría que el worker produce la forma de la respuesta de
la API: dos lugares armando el mismo payload, libres de no coincidir. Y
sería una carrera de *lost update* entre esa escritura y lo que la API
esté cacheando en ese instante. Borrar no tiene ninguno de los dos
problemas: el próximo read falla, va a la base y cachea lo que encuentra.

### 2. La clave lleva el dueño

```
job:{userId}:{jobId}
```

**Esto no es prolijidad, es la diferencia entre un cache y un bypass de
autorización.** `GET /jobs/{id}` responde **404** para un job ajeno
([ADR de nada, está en el controller](../../upload-api-service/src/main/java/ar/facturas/upload/adapters/in/rest/JobController.java)),
y esa verificación corre contra la **fila de la base**. Un hit del cache
se saltea la base — entonces se saltea la verificación.

Con la clave sin el usuario, el polling de uno llenaría una entrada que
se le serviría al otro. Con el usuario adentro, el equivocado
simplemente falla, cae a la base y ahí se lo rechaza como corresponde. El
costo es una entrada desperdiciada por job, que es nada: un job tiene un
solo dueño y al otro usuario nunca se le iba a contestar.

### 3. Degrada, no falla

Todos los métodos de `JobCache` se comen su excepción. Un miss y un error
son lo mismo para el endpoint.

Un cache que se lleva el request puesto cuando no está disponible es peor
que no tener cache: convierte un componente opcional en obligatorio. Así,
la API sigue contestando desde Postgres mientras Redis reinicia.

Una entrada **ilegible** también se trata como miss, y pasa por un
motivo real: `JobResponse` cambia de forma y las entradas que escribió el
deploy anterior siguen en Redis hasta un TTL. Fallar por eso haría de
cada deploy un corte breve; caer a la base lo hace una pérdida breve de
cacheo, que nadie nota.

### 4. El TTL es la garantía de último recurso

30 segundos, y son segundos y no minutos por el modo de falla: la
invalidación la manda el worker, y si el worker no puede hablar con Redis
la entrada vieja se queda hasta que expire.

Es la misma forma que aparece dos veces más en este sistema — el barrido
periódico detrás del camino rápido del relay
([005](005-transactional-outbox.md)) y el reaper detrás de la cola
([012](012-una-extraccion-fallida-se-ackea.md)): el camino rápido puede
fallar y algo más lento y más tonto garantiza el resultado. Acá la
garantía es solo "dentro del TTL", y por eso el TTL es chico.

## Consecuencias

**La invalidación cruza procesos, que es lo que la hace valer.** El
worker necesita el `userId` para armar la clave, así que paga una
consulta extra después de extraer. Vale: sin el `userId` la clave es
insegura, y ese no es un costo negociable.

**Redis sin persistencia** (`--save "" --appendonly no`). Un cache que
sobrevive al reinicio es un cache que puede devolver algo de antes de un
deploy.

**Un hit no abre transacción.** `JobController` escribe su transacción de
lectura con un `TransactionTemplate` en vez de anotar el handler: con
`@Transactional` en el método, cada poll tomaría una conexión del pool
para contestar desde Redis, que es la mitad de lo que el cache venía a
ahorrar.

**`JobCache` vive en `shared-infrastructure`** y guarda un string opaco.
La API serializa su propia respuesta adentro y la vuelve a parsear;
el módulo no sabe qué es un `JobResponse`, así que el DTO de la API no
entra en un módulo del que el worker también depende. El worker solo
llama a `evict`.

## Medido contra el stack real

Con los siete contenedores arriba, forzando el orden en vez de esperarlo:

| | |
|---|---|
| Worker **detenido**, subo un comprobante | el job se queda en `PENDING` |
| Leo `GET /jobs/{id}` | Redis pasa de 0 a 1 clave: `job:00000000-…-0001:c8fbc791-…` |
| La clave | lleva el `userId` **y** el `jobId`, TTL 30 s, valor = el `JobResponse` serializado |
| Vuelvo a leer con el worker muerto | sigue diciendo `PENDING` — sale del cache |
| **Levanto el worker** | extrae y **borra la entrada desde el otro proceso**: Redis vuelve a 0 |
| Leo de nuevo | `EXTRACTED`. El endpoint dejó de decir "procesando" sin que nadie se lo diga |

La primera vez que lo probé sin detener el worker, la clave no aparecía y
parecía que el cacheo no funcionaba. Funcionaba: el worker terminaba el
job e invalidaba la entrada **entre mi lectura y mi chequeo**. Detener el
worker no fue para que el test pasara, fue para poder ver los dos pasos
por separado.

## Alternativas descartadas

**Write-through: el worker escribe el valor nuevo.** Evita el miss
siguiente. A cambio, dos servicios armando el mismo payload y una
carrera de lost update. El miss cuesta una consulta.

**Cachear la entidad `Job` en vez de la respuesta.** Deja la
serialización de la API fuera del cache, pero obliga a reconstruir el DTO
en cada hit —incluido el `ExtractedInvoiceData`, que es otra consulta— y
el ahorro se va.

**`@Cacheable` de Spring.** Menos código. Esconde justo lo que importa
acá: la clave la arma la anotación, y con `#jobId` como key —lo natural de
escribir— el cache queda sin el dueño y se vuelve el bypass del punto 2.
Hacerlo a mano deja la clave a la vista.

**No cachear.** Es defendible hasta que exista el frontend que hace el
polling. Se hace ahora igual porque es lo que cierra la Fase 2, y porque
la invalidación entre dos procesos es la parte del diseño que no se puede
probar de otra forma.
