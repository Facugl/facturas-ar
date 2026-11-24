# 015 — Sesión con cookie, y no un token

**Estado:** aceptada · implementada
**Fecha:** 2026-11-24

## Contexto

Hasta ahora no había autenticación. Había un sustituto: un
`DevCurrentUser` que devolvía siempre el mismo `userId` sembrado, para
que el resto del sistema pudiera construirse sobre la idea de que los
comprobantes tienen dueño sin tener todavía de dónde sacar quién es.

Eso dejaba un agujero que no era de código sino de verificación: el
**criterio de aceptación 7** —*un usuario no ve los comprobantes de
otro*— no se podía testear. Todas las consultas filtran por `user_id` y
`GET /jobs/{id}` responde 404 para un job ajeno, pero con un solo usuario
posible cualquier par de "usuarios" era el mismo usuario. El filtro
estaba escrito y nunca ejercitado.

El alcance de la autenticación está decidido desde el README §3: **email
y contraseña, sin roles ni RBAC**. Lo único que quedaba por decidir es
**cómo viaja la credencial**.

## Decisión

Sesión del lado del servidor, en una cookie `HttpOnly`. No un JWT.

El criterio no fue "qué se usa más" sino qué compra cada uno acá.

### 1. El logout funciona de verdad

La sesión es estado del servidor: invalidarla corta el acceso en ese
instante. Un JWT es válido hasta que expira, y "cerrar sesión" significa
pedirle al cliente que se olvide de algo que sigue sirviendo. Para
revocar de verdad hay que mantener una lista de tokens revocados — o sea,
estado del servidor, que es exactamente lo que el token venía a evitar.

Esto está testeado y es el test que más me gusta de los siete: después
del logout **el cliente sigue teniendo la cookie** —nadie se la quitó— y
no vale nada.

### 2. No hay nada que un script pueda robar

Una cookie `HttpOnly` es inalcanzable desde JavaScript, así que un XSS no
puede exfiltrarla. Un token tiene que vivir en algún lugar que la página
pueda leer: `localStorage` lo deja a mano de cualquier script inyectado,
y guardarlo en memoria lo pierde en cada recarga.

### 3. No hay biblioteca ni secreto que rotar

Sin dependencia de JWT, sin clave de firma en la configuración, sin
decisión sobre algoritmo ni sobre expiración. Para un proyecto de una
persona eso es menos superficie de error.

### Y lo que el JWT vendía, acá no sirve

El argumento real a favor del token es que no hay estado compartido, así
cualquier instancia valida sin consultar a nadie. Acá hay **un solo
proceso de API**, y el día que haya varios, Spring Session sobre Redis es
una dependencia que este stack **ya tiene** para el cache de jobs
([014](014-el-cache-se-invalida-y-la-clave-lleva-el-dueno.md)). Elegir un
token para no compartir estado de sesión sería pagar por un problema que
ya está resuelto.

## Consecuencias

### CSRF, que es el precio

El navegador manda la cookie sola, venga el request de nuestra página o
de la de otro. Por eso el token de CSRF **se maneja, no se desactiva**:

- `CookieCsrfTokenRepository.withHttpOnlyFalse()` — el servidor pone
  `XSRF-TOKEN` donde el JavaScript del frontend puede leerla, y el
  frontend la devuelve en `X-XSRF-TOKEN`. Un POST sin ese eco no lo hizo
  nuestra página.
- La cookie de sesión va `HttpOnly` y `SameSite=Lax`. `Lax` ya corta el
  caso clásico del POST cross-site; el token cubre lo que `SameSite` no
  garantiza en navegadores viejos.

Que esto está prendido también está testeado: un POST **con sesión
válida** y sin el token responde 403, y la sesión sobrevive porque el
logout nunca corrió. Estar logueado es justo la situación de la que
depende el ataque.

### Lo público es corto

`/auth/register`, `/auth/login`, `/actuator/health` y
`/actuator/prometheus`. Los dos últimos porque el healthcheck de Docker y
el scrape de Prometheus corren desde adentro de la red de compose y
ninguno puede loguearse. El resto de Actuator pide sesión, incluido
`/actuator/metrics`, que se puede consultar valor por valor.

En el mismo cambio los detalles del health pasaron a `when-authorized`:
con `always` y el endpoint público, un request anónimo recibía el
proveedor de la base, la query de validación y el espacio libre en disco.

### Sin roles, a propósito

`AuthenticatedUser` no tiene autoridades, y la lista vacía está explícita
en vez de omitida. Estar autenticado es el único permiso que existe; lo
que separa los datos de un usuario de los de otro es el `user_id` de la
fila, no un rol.

## Las dos cosas que salieron mal

Las dos las encontró el test, no la lectura del código, y las dos
producían **el mismo síntoma equivocado**: `POST /auth/register`, que es
un endpoint `permitAll`, respondía **401**.

### El servidor nunca emitía la cookie de CSRF

Spring Security difiere la resolución del token: el filtro deja un
`Supplier<CsrfToken>` en el request y nada genera un token hasta que
alguien lo llama. Para una página renderizada en el servidor está bien
—el template lee el token, así que renderizar lo resuelve—. Para una SPA
está mal: la página necesita la cookie **antes** de su primera escritura,
y en un `GET` nada toca el supplier.

La solución es dejar el nombre del atributo **sin configurar**
(`setCsrfRequestAttributeName(null)`): sin nombre, el handler le pregunta
al token cuál es su nombre de parámetro para armarlo — y preguntarle algo
al token resuelve el supplier, que lo genera y escribe la cookie. Oscuro,
y es la forma documentada.

El log de `TRACE` es lo que lo delató, y conviene leerlo entero:

```
s.s.w.c.CsrfTokenRequestAttributeHandler : Wrote a CSRF token to the
                                           request attributes: [_csrf, …]
o.s.s.web.csrf.CsrfTokenRequestHandler   : Did not find a CSRF token in
                                           the [X-XSRF-TOKEN] header
```

Dice que escribió el token. Escribió el *atributo*, no la cookie.

### Un 403 llegaba al cliente como 401

Y este es el que más tiempo costó, porque mandaba a mirar el lugar
equivocado. Una respuesta de error se produce **haciendo forward a
`/error`**, y ese forward vuelve a pasar por la cadena de filtros. Con
`/error` bajo `authenticated()`, cualquier error de un llamador sin
sesión vuelve como 401 sea lo que sea en realidad: el rechazo de CSRF
contestó 403, hizo forward, y el cliente leyó 401 en un endpoint
`permitAll`.

`/error` ahora es `permitAll`. La conclusión que vale guardar es que el
código de estado que ve el cliente puede no ser el que la aplicación
generó.

## Medido contra el stack real

Con los ocho contenedores arriba, corriendo exactamente los comandos que
quedó documentados en el README:

| | |
|---|---|
| `GET /actuator/health` anónimo | devuelve `XSRF-TOKEN`, y el body trae solo `status` y `groups` — nada del proveedor de la base ni del disco |
| `POST /auth/register` | **201**. La segunda vez con el mismo email, **409** |
| `POST /auth/login` | **200** con `{"id":"55d53cc4-…","email":"…"}`, y deja `JSESSIONID` |
| El frasco de cookies | `JSESSIONID` marcada `#HttpOnly_`, `XSRF-TOKEN` sin marcar — la de sesión inalcanzable desde JS, la del token legible, que es justo al revés de lo que parece y es lo correcto |
| `GET /auth/me` con sesión / sin sesión | **200** / **401** |
| `POST /auth/logout` **sin** el header | **403**, y la sesión sobrevive: el logout nunca corrió |
| `POST /documents` con sesión | **202**, y el worker lo termina del otro lado de la cola |
| **El job, pedido por un segundo usuario registrado** | **404**. Criterio 7 |
| Su export del mismo período | solo la fila de encabezados, con BOM |
| `POST /auth/logout` con el header | **204**, y después **401** con *la misma cookie* — en `/auth/me` y en el job que hasta hace un segundo era suyo |

La última fila es la que un JWT no puede dar. Al cliente no se le quitó
nada: sigue teniendo la credencial, y no sirve.

## Alternativas descartadas

**JWT en el header `Authorization`.** Lo de arriba: logout que no cierra
nada, el token expuesto a cualquier script, y statelessness que no compra
nada con un proceso. Es la opción correcta cuando hay varios clientes que
no son navegadores, o cuando el emisor y el validador son servicios
distintos. Ninguna de las dos pasa acá.

**JWT en una cookie `HttpOnly`.** El punto 2 vuelve, el 1 no: sigue sin
haber revocación. Queda lo peor de los dos — CSRF igual, y un secreto que
mantener.

**HTTP Basic.** Dos líneas de configuración. El navegador guarda las
credenciales y las reenvía en cada request, no hay logout, y el diálogo
nativo no se puede estilar. Se descartó incluso para lo interno.

**Desactivar CSRF porque "es una API".** Es el atajo que hace que el
punto 1 deje de valer: con la cookie de sesión prendida y CSRF apagado,
la página de cualquiera puede hacer POST en nombre del usuario logueado.
Si se apaga CSRF, hay que dejar de usar cookies — y ahí sí el token es la
respuesta.

**OAuth / login con Google.** Menos contraseñas que guardar, y es lo que
elegiría un producto real. Para un proyecto de portfolio agrega una
dependencia externa, credenciales de aplicación y un callback a
configurar, y oculta justo la parte que vale mostrar: cómo se guarda una
contraseña y cómo se sostiene una sesión.
