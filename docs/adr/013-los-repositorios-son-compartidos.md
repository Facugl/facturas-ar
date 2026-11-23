# 013 — Los repositorios y el adapter de S3 son compartidos, no duplicados

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
shared-infrastructure  repositorios + adapter de S3. Spring, a proposito
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

**Un modulo por recurso: `shared-persistence` y `shared-storage`.** Más
preciso y dos módulos para ocho clases. La regla que los une es una sola
—"los recursos que los dos servicios comparten"— así que entran juntos
hasta que haya un motivo para separarlos.
