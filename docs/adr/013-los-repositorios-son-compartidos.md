# 013 — La base, el bucket y la topología del broker son compartidos, no duplicados

**Estado:** aceptada · implementada
**Fecha:** 2026-11-23

## Contexto

El [ADR 006](006-dos-servicios-una-base.md) decidió dos servicios sobre
una base y cerró con esta línea:

> **`shared-domain` existe por esto.** Las entidades viven en un módulo
> común porque los dos servicios las usan contra la misma base.

Al ir a separar el worker quedó claro que la frase estaba a medias. Las
entidades no son lo único compartido. Los tres repositorios que el worker
necesita los usa **también** la API:

| Repositorio | Lado API | Lado worker |
|---|---|---|
| `JobRepository` | `JobController`, `RequeueJob`, `RecoverJobs` | `ExtractComprobante` |
| `DocumentRepository` | `UploadComprobante` | `ExtractComprobante` |
| `ExtractedInvoiceDataRepository` | `JobController`, `ExportComprobantes` | `ExtractComprobante` |

Y el adapter de S3 también: la API **guarda** el archivo, el worker lo
**lee**.

## Decisión

Un módulo `shared-infrastructure` con los repositorios y el adapter de
almacenamiento. **Una base, un mapeo de esa base.**

```
shared-domain          entidades, value objects, enums. Cero framework
shared-events          contratos de los mensajes. Cero dependencias
shared-infrastructure  repositorios + adapter de S3 + topologia del
                       broker. Spring, a proposito
```

Cada servicio hace `@Import(SharedInfrastructure.class)` y recibe el
`@EntityScan`, el `@EnableJpaRepositories` y el adapter de S3 en una
línea.

## Consecuencias

**El `UPDATE` condicional del claim existe una sola vez.** Es la razón
concreta y es suficiente por sí sola. Ese `UPDATE` es lo único que
impide que dos workers procesen el mismo job ([ADR 007](007-claim-atomico.md)),
tiene veinte líneas de javadoc explicando por qué no puede ser un
`SELECT` seguido de un `UPDATE`, y duplicarlo en dos módulos sería
duplicar la pieza más crítica del sistema en el lugar donde menos se
nota que se desincronizó.

**`shared-domain` no se contamina.** Poner los repositorios ahí habría
metido Spring Data en el módulo que defiende "cero framework" con un
comentario de treinta líneas en su pom. Este módulo existe justamente
para no mover esa línea: acá Spring entra porque esto **son** adapters.

**`@EnableJpaRepositories` apaga el escaneo automático de Boot.** Es el
efecto buscado, no un efecto colateral: todo repositorio del proyecto
vive en `ar.facturas.infrastructure.persistence`, así que uno definido en
otra parte es un error y conviene que no se cablee en vez de que funcione
en silencio.

**Un servicio que llega después no se puede cablear a medias.** Antes
cada aplicación llevaba su propia línea de `@EntityScan`; olvidarla
arranca el contexto y recién falla en el primer repositorio. Ahora es un
`@Import` o no arranca.

**Flyway queda afuera a propósito.** El esquema tiene un dueño y es la
API. Dos servicios compitiendo por migrar la misma base al arrancar es,
en el mejor caso, contención de locks. El worker corre con
`ddl-auto: validate` y confía en que el esquema ya está.

**La topología del broker, en cambio, la declaran los dos** — y la
diferencia con Flyway es el punto. Declarar un exchange es idempotente y
sin estado; una migración es una secuencia ordenada de cambios y dos
corriendo a la vez es peligroso de verdad. Misma pregunta, respuesta
distinta, porque las operaciones son distintas en especie.

> **Esto empezó al revés y se corrigió al primer test.** La topología
> vivía en la API, con el argumento de que dos servicios declarando la
> misma cosa con argumentos posiblemente distintos es una falla de
> arranque esperando a pasar. La premisa estaba mal: los argumentos salen
> de **una sola clase** que los dos importan, así que no pueden diferir.
> Lo que el dueño único sí costaba era real — el worker no arrancaba
> contra un broker donde la API no hubiera corrido, que en un test es un
> contexto que no levanta y en producción es un crash loop hasta que la
> API aparece.

**El costo real:** un cambio de esquema afecta a los dos servicios, y
ahora también un cambio de repositorio. El ADR 006 ya aceptó ese costo —
*"con un solo desarrollador es irrelevante; con equipos independientes
dejaría de serlo"*— y esto no lo empeora, lo hace visible.

## Alternativas descartadas

**Duplicar las interfaces en cada servicio, cada uno declarando lo que
usa.** Tiene un argumento bueno: documenta exactamente qué toca cada
servicio, y un cambio en la API no puede romper al worker. Se descarta
por el claim. Dos copias de ese `UPDATE` es la clase de duplicación que
se desvía sin que nadie lo note, porque los dos lados siguen compilando
y los tests de cada módulo siguen pasando.

**Los repositorios en `shared-domain`.** Es a donde apunta la línea del
ADR 006 leída literalmente, y rompe lo único que ese módulo promete.

**Un modulo por recurso: `shared-persistence`, `shared-storage` y
`shared-messaging`.** Más preciso, y tres módulos para nueve clases. La
regla que los une es una sola —"cómo se llega a los recursos que los dos
servicios comparten"— así que entran juntos hasta que haya un motivo
para separarlos.

## Nota sobre el dependencia de test del worker a la API

Separar el worker partió en dos la única prueba automática del
**criterio de aceptación 8**: la API publica y el worker consume, así que
ningún módulo solo puede demostrar el camino completo. La alternativa era
degradarlo a una verificación manual contra `docker compose`, y es el
criterio que el proyecto señala para justificar que la cola exista.

Así que `ocr-worker-service` tiene una dependencia **de test** a
`upload-api-service`, y un solo test —`EndToEndTest`— levanta los dos
contextos. La flecha va en esa dirección porque la API no depende del
worker; al revés sería un ciclo. Nada de `src/main` cruza esa línea.

Eso obligó a dos cosas que vale anotar:

- **El jar ejecutable de la API lleva clasificador** (`-boot`). Por
  defecto `repackage` reemplaza el artefacto principal por el ejecutable,
  y un jar ejecutable no sirve como dependencia: sus clases viven en
  `BOOT-INF/classes` y nadie las ve.
- **Dos módulos con `application.yml` compiten.** Cuando `EndToEndTest`
  levanta los dos contextos, Spring lee uno solo de los dos archivos, así
  que las propiedades de la API hay que repetirlas en la configuración
  del test. Se descubre como un placeholder sin resolver, que es una
  forma oscura de enterarse.
