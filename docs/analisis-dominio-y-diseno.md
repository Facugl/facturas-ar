# Análisis de dominio y diseño — facturas-ar

> Documento de trabajo. Complementa al README: el README cuenta **qué** se construye, este documento explica **por qué** el problema es así, **qué casos de uso** se derivan de eso y **qué arquitectura** sostiene esos casos de uso.
>
> Última revisión: 2026-10-17.

---

## 0. El hallazgo que reordena el proyecto

Antes de cualquier otra cosa: **las facturas electrónicas argentinas llevan desde 2021 un código QR obligatorio que contiene los datos del comprobante en JSON.**

No es un link a un sitio de consulta. El QR codifica literalmente:

```
https://www.arca.gob.ar/fe/qr/?p={JSON del comprobante en Base64}
```

Y el JSON (versión 1 de la especificación) es:

```json
{"ver":1,"fecha":"2020-10-13","cuit":30000000007,"ptoVta":10,"tipoCmp":1,
 "nroCmp":94,"importe":12100,"moneda":"DOL","ctz":65,"tipoDocRec":80,
 "nroDocRec":20000000001,"tipoCodAut":"E","codAut":70417054367476}
```

### Qué significa esto

El README actual plantea OCR como el motor del sistema: foto → Tesseract → regex → campos con confianza parcial. Pero para cualquier comprobante electrónico emitido después de junio de 2021 — que es la abrumadora mayoría de lo que alguien va a subir — **seis de los campos más importantes se leen del QR con precisión perfecta y cero ambigüedad**:

| Campo del MVP | Vía QR | Vía OCR |
|---|---|---|
| CUIT del emisor | exacto | regex frágil, confunde dígitos |
| Fecha de emisión | exacto (RFC3339) | ambigüedad dd/mm vs mm/dd, dígitos borrosos |
| Tipo de comprobante | exacto (código numérico) | heurística de posición, poco confiable |
| Punto de venta | exacto | regex sobre "0001-00001234" |
| Número de comprobante | exacto | ídem |
| Importe total | exacto | el campo más crítico, y el que más duele si sale mal |
| CAE | exacto | 14 dígitos de OCR = garantía de error |
| Razón social del emisor | **no viene** | OCR necesario |
| Subtotal / neto | **no viene** | OCR necesario |
| IVA discriminado | **no viene** | OCR necesario |
| Condición IVA receptor | **no viene** | OCR necesario |
| Percepciones | **no viene** | OCR necesario |

Esto no debilita el proyecto: **lo mejora**. Un sistema que hace "OCR a todo" es lo que haría cualquiera; un sistema que reconoce que el dominio ya le da una fuente de verdad estructurada y la usa primero, dejando OCR para lo que falta, es una decisión de ingeniería que se defiende sola en una entrevista.

### Consecuencias de diseño

1. **El pipeline deja de ser lineal y pasa a ser una cadena de estrategias con fusión por confianza.** QR primero (confianza 1.0), texto embebido del PDF después, OCR al final como relleno. Cada campo se queda con el valor de la fuente más confiable que lo haya producido.
2. **La precisión del MVP deja de depender de Tesseract.** El criterio de aceptación "70% de campos clave correctos" pasa a ser ~100% en comprobantes con QR legible, y el riesgo se concentra en razón social e IVA desagregado.
3. **La validación contra ARCA deja de ser "v2".** Con el CAE, CUIT, tipo, punto de venta, número, fecha e importe en la mano, constatar el comprobante es una llamada más. Pasa de ser una feature cara a ser casi gratis.
4. **Aparece una verificación cruzada potente.** Si el OCR lee un total de $ 12.100 y el QR dice `importe: 12100`, coinciden → confianza alta. Si no coinciden, el QR gana y se marca la discrepancia. Eso es una señal de calidad real, no un score inventado.
5. **El preprocesamiento de imagen cambia de objetivo.** Deskew y binarización dejan de ser "para que Tesseract lea mejor" y pasan a ser, en primer lugar, "para que el detector de QR encuentre el código". ZXing es bastante tolerante, pero una foto torcida con sombra le cuesta.

> **ADR a escribir:** `004-qr-first-ocr-fallback.md`. Es el ADR más valioso del proyecto.

---

## 1. La problemática

### 1.1 El trabajo manual que se quiere eliminar

Alguien junta comprobantes durante el mes — PDFs que le llegaron por mail, fotos de tickets, papeles en una carpeta — y en algún momento tiene que convertir esa pila en filas de una planilla. Por cada comprobante: abrir, leer, tipear siete u ocho campos, pasar al siguiente. Entre 30 segundos y dos minutos por comprobante según el formato y el estado del papel.

El costo no es solo el tiempo. Es que:

- **Se posterga.** Nadie hace esto el día que recibe la factura, entonces se acumula y se vuelve una tarde entera.
- **Se cometen errores de tipeo** justo en los campos donde más duelen (CUIT, importe).
- **Se pierden comprobantes.** El ticket de papel se borra, el mail se traspapela.
- **El archivo original queda desconectado del dato.** La planilla dice "$ 12.100 de Proveedor X" pero el PDF que lo respalda está en otra carpeta, o no está.

### 1.2 Tres perfiles con problemas distintos

Esto es lo más importante de definir bien, porque **cada perfil implica un producto diferente** y el README actual los mezcla.

#### A. Monotributista

- **Qué necesita:** controlar cuánto facturó (para no pasarse del límite de su categoría y para la recategorización semestral) y tener a mano sus gastos.
- **Qué campos le importan:** fecha, proveedor, total. **Nada más.**
- **Qué NO le importa:** el IVA discriminado no le sirve — no toma crédito fiscal. El subtotal tampoco.
- **Volumen:** bajo, decenas por mes.
- **Veredicto:** es el perfil más fácil de servir, y el QR le resuelve el 100% del caso. También es el perfil con **menos dolor real**, porque su obligación fiscal es liviana.

#### B. Responsable inscripto (o quien le lleva los libros)

- **Qué necesita:** cargar sus comprobantes recibidos al **Libro IVA Digital** (RG 4597), que reemplazó al viejo régimen CITI de compras y ventas.
- **Qué campos le importan:** todo lo anterior **más** el neto gravado, el IVA discriminado **por alícuota** (21%, 10,5%, 27% pueden convivir en una misma factura A), los conceptos no gravados y exentos, y las **percepciones** de IVA e Ingresos Brutos.
- **Veredicto:** es donde está el dolor real y el dinero. También es **mucho más difícil**, porque justamente los campos que necesita son los que el QR no trae y los que peor salen por OCR.

#### C. Contador / estudio contable

- **Qué necesita:** lo de (B) pero multiplicado por 40 clientes, con separación por cliente y entrega en el formato que come su software (Tango, Bejerman, Xubio, Colppy).
- **Veredicto:** multi-tenant, importación masiva, formatos de export específicos. Fuera de alcance de un proyecto de portfolio, pero vale saber que existe porque define hacia dónde escalaría.

> **Decisión tomada (2026-10-17): v1 apunta a (A) monotributista**, con el modelo de datos preparado para (B). Razón: (A) se puede resolver completo y bien con el QR, lo que da un MVP que *funciona de verdad* en vez de uno que acierta el 70%. Y un MVP que funciona es mucho mejor material de portfolio que uno ambicioso que falla.
>
> **Consecuencia directa sobre el alcance:** si el QR entrega fecha, CUIT, tipo, punto de venta, número, importe total y CAE, y el monotributista no necesita neto ni IVA, entonces **lo único que queda a cargo del texto embebido y del OCR es la razón social del emisor**. Eso reduce la superficie de la parte frágil del sistema a un solo campo, y hace que el criterio de "MVP que funciona perfecto" sea realmente alcanzable.

### 1.3 Lo que ya existe y es gratis: "Mis Comprobantes" de ARCA

Esto hay que mirarlo de frente, porque en una entrevista te lo van a preguntar.

ARCA ofrece el servicio **Mis Comprobantes**, donde cualquier contribuyente con clave fiscal ve sus comprobantes **emitidos** y **recibidos**, filtra por período (hasta 365 días) y los **exporta a CSV/Excel**. Gratis. Sin instalar nada.

**Por qué el proyecto sigue teniendo sentido — y hay que poder decirlo en una frase:**

1. **Solo aparece lo que se emitió a tu CUIT.** Si pagaste algo y te dieron una **factura B a consumidor final** sin identificarte, ese comprobante **no existe** en Mis Comprobantes. Toda la caja chica, los tickets de combustible, los gastos de viaje, las compras en comercios chicos: no están.
2. **No están los comprobantes no electrónicos.** Tickets fiscales viejos, comprobantes manuales, recibos en papel.
3. **Los datos vienen incompletos.** Hay reportes de que comprobantes con percepciones no se muestran correctamente, y los montos en moneda extranjera vienen sin convertir.
4. **No hay API.** La única vía es entrar al portal con clave fiscal y descargar a mano. No se puede automatizar sin scraping (que no es el objetivo del proyecto y es frágil y legalmente gris).
5. **No te guarda el archivo.** Te da la fila, no el PDF ni la foto. Para una auditoría necesitás el respaldo.

**El posicionamiento honesto, en una frase:** *"Mis Comprobantes cubre lo que ARCA ya sabe de vos. Esto cubre la pila de papel y PDFs que ARCA no sabe, y te deja el archivo original pegado al dato."*

Escribí esto en el README. Un proyecto de portfolio que demuestra que investigó el estado del arte y sabe dónde se para vale el doble.

### 1.4 Glosario mínimo del dominio

Sin esto el modelo de datos sale mal.

| Término | Qué es | Por qué importa al diseño |
|---|---|---|
| **CAE** | Código de Autorización Electrónico, 14 dígitos, lo otorga ARCA al autorizar el comprobante | Identificador único real del comprobante. Mejor clave de deduplicación que un hash del archivo |
| **CAEA** | Variante anticipada del CAE, para quien emite masivamente | El campo `tipoCodAut` del QR distingue `"E"` (CAE) de `"A"` (CAEA) |
| **Punto de venta** | Hasta 5 dígitos. Identifica la "caja" o sucursal del emisor | La identidad de un comprobante es la tupla `(cuit, tipoCmp, ptoVta, nroCmp)`, no solo el número |
| **Tipo de comprobante** | Código numérico de tabla oficial | Determina la clase y el signo. Ver 1.5 |
| **Clase A / B / C / M / E** | Define si el IVA va discriminado | **A**: entre responsables inscriptos, IVA discriminado. **B**: a consumidor final o monotributista, IVA incluido sin discriminar. **C**: emitida por monotributista o exento, **sin IVA**. **M**: régimen especial de control. **E**: exportación |
| **Nota de crédito** | Anula o reduce un comprobante anterior | **Resta.** Si el export suma todo sin signo, el total está mal |
| **Alícuota** | 21%, 10,5%, 27%, 2,5%, 5%, 0% | Una factura A puede tener **varias a la vez**. `vat_amount` como un solo número es una simplificación, no una verdad |
| **Percepción** | Adelanto de impuesto (IVA o IIBB) que el proveedor cobra de más | No es IVA ni es neto. Si no se modela, los totales no cierran |
| **Crédito fiscal** | El IVA de una compra que un RI descuenta del IVA que debe | La razón por la que (B) necesita el IVA discriminado y (A) no |
| **Libro IVA Digital** | RG 4597. Registro electrónico obligatorio para responsables inscriptos | El destino final del dato para el perfil (B) |
| **Constatación** | Verificar contra ARCA que un CAE es auténtico y los datos coinciden | Detecta facturas apócrifas. Con el QR es casi gratis |

### 1.5 Códigos de tipo de comprobante (subconjunto relevante)

De la tabla oficial de ARCA (`afip.gob.ar/fe/documentos/TABLACOMPROBANTES.xls` — descargala, se actualiza).

| Código | Comprobante | Signo |
|---|---|---|
| 1 | Factura A | + |
| 2 | Nota de Débito A | + |
| 3 | Nota de Crédito A | **−** |
| 6 | Factura B | + |
| 7 | Nota de Débito B | + |
| 8 | Nota de Crédito B | **−** |
| 11 | Factura C | + |
| 12 | Nota de Débito C | + |
| 13 | Nota de Crédito C | **−** |
| 19 | Factura E (exportación) | + |
| 51 | Factura M | + |
| 81 / 82 | Tique Factura A / B | + |
| 111 | Tique Factura C | + |
| 201 / 206 / 211 | FCE MiPyME A / B / C | + |

> El signo va en el enum, no en un `if` perdido en el servicio de export.

### 1.6 El dígito verificador del CUIT

Un CUIT no es "11 números cualesquiera": el último dígito es verificador (módulo 11). Esto da dos cosas gratis:

1. **Validación:** descartar CUITs mal leídos por OCR antes de mostrárselos al usuario.
2. **Corrección asistida:** si el OCR leyó 11 dígitos y el verificador no cierra, probar las confusiones típicas (`8↔3`, `5↔6`, `1↔7`, `0↔O`) y ver si alguna combinación valida. Con una sola corrección candidata que valide, la probabilidad de acierto es alta.

```
pesos  = [5,4,3,2,7,6,5,4,3,2]   // sobre los primeros 10 dígitos
suma   = Σ digito[i] * pesos[i]
dv     = 11 - (suma % 11)
si dv == 11 → dv = 0
si dv == 10 → dv = 9
```

Esto vive en un **value object `Cuit`** que no se puede instanciar inválido. Es el ejemplo de manual de por qué los value objects existen.

---

## 2. Casos de uso

### 2.1 Actores

| Actor | Descripción |
|---|---|
| **Usuario** | Dueño de los comprobantes. Un solo rol, sin jerarquía |
| **Sistema de extracción** | Actor secundario, asíncrono. El worker |
| **ARCA** | Sistema externo. Solo para constatación de CAE (opcional) |

### 2.2 Mapa de casos de uso

```
Gestión de cuenta
  CU-01  Registrarse
  CU-02  Iniciar sesión

Ingesta
  CU-03  Subir un comprobante                      ← núcleo
  CU-04  Detectar duplicado (por archivo al subir, por comprobante al extraer)
  CU-05  Seguir el estado del procesamiento

Extracción  (sistema)
  CU-06  Extraer datos del comprobante             ← núcleo
  CU-07  Reintentar una extracción fallida
  CU-08  Constatar el CAE contra ARCA              (opcional v1)

Revisión
  CU-09  Revisar y corregir los datos extraídos    ← núcleo
  CU-10  Confirmar un comprobante
  CU-11  Descartar un comprobante

Consulta y salida
  CU-12  Listar y filtrar el historial
  CU-13  Exportar un rango a CSV                   ← núcleo
  CU-14  Descargar el archivo original
```

Los cuatro marcados como núcleo son el *vertical slice* del MVP: si esos cuatro funcionan de punta a punta, el proyecto existe. El resto es superficie.

### 2.3 Fichas de los casos de uso núcleo

---

#### CU-03 — Subir un comprobante

**Actor:** Usuario · **Disparador:** elige un archivo o saca una foto

**Precondiciones:** autenticado.

**Flujo principal**
1. El usuario selecciona un PDF, JPG o PNG (en mobile, directo desde la cámara).
2. El sistema valida formato y tamaño (≤ 10 MB).
3. Calcula el `content_hash` (SHA-256) del archivo.
4. Guarda el binario en el object storage bajo una `storage_key` opaca.
5. Persiste un `Document` y un `Job` en estado `PENDING`.
6. Encola la extracción.
7. Devuelve el `jobId` y redirige a la pantalla de estado.

**Extensiones**
- *2a.* Formato no soportado → rechazo inmediato con mensaje explícito. No se crea `Document`.
- *3a.* El hash ya existe para ese usuario → CU-04.
- *4a.* El storage no responde → se rechaza la subida entera. **No se crea un `Job` que apunte a un archivo que no está.**

**Criterio de aceptación:** subir un PDF de 2 MB desde un celular con 4G devuelve `jobId` en menos de 3 segundos y el archivo es recuperable después por su `storage_key`.

---

#### CU-06 — Extraer datos del comprobante

**Actor:** sistema (worker) · **Disparador:** un `Job` en `PENDING`

Este es el corazón del proyecto. El flujo en cascada:

```
1. Claim atómico: UPDATE ... SET status='PROCESSING' WHERE status='PENDING'
     0 filas → ack y descartar (duplicado, o estado no procesable)
2. Descargar el archivo por storage_key

3. ── Clasificar el origen ──────────────────────────────
     PDF con texto extraíble (>50 chars)  → PDF_TEXT
     PDF sin texto                        → PDF_SCANNED
     Imagen                               → IMAGE

4. ── Estrategia QR  (confianza 1.0) ───────────────────
     Renderizar la primera página a imagen si es PDF
     Buscar QR con ZXing (probar rotaciones 0/90/180/270)
     ¿Encontrado?
       → parsear la URL, tomar el parámetro p
       → decodificar Base64 → JSON → validar contra el schema v1
       → validar el dígito verificador del CUIT
       → poblar: fecha, cuit, ptoVta, tipoCmp, nroCmp,
                 importe, moneda, ctz, docRec, CAE
       → marcar la fuente de cada campo como QR

5. ── Estrategia texto embebido  (confianza ~0.9) ──────
     Si PDF_TEXT: extraer texto con PDFBox
     Aplicar las reglas de parseo sobre texto limpio
     Poblar SOLO los campos que el QR no trajo:
       razón social, neto, IVA por alícuota, percepciones

6. ── Estrategia OCR  (confianza 0.3–0.8) ──────────────
     Si todavía faltan campos y el origen lo amerita:
       preprocesar (gris → deskew → binarización adaptativa)
       Tesseract con traineddata en español
       aplicar las mismas reglas de parseo sobre el texto OCR
       poblar los campos que sigan vacíos

7. ── Fusionar y verificar ─────────────────────────────
     Por cada campo, gana la fuente de mayor confianza
     Verificaciones cruzadas:
       ¿total del QR == total leído por OCR?       → refuerza
       ¿neto + IVA + percepciones == total?        → refuerza
       ¿el dígito verificador del CUIT cierra?     → refuerza
       Discrepancia → el QR gana, se registra la discrepancia

8. ¿Este usuario ya tiene (cuit, tipo, ptoVta, nro)?
     sí → Job → DUPLICATE + duplicate_of_job_id, sin fila de datos
     no → persistir ExtractedInvoiceData + Job → EXTRACTED (1 transacción)
          si salta uq_eid_usuario_comprobante (carrera): → DUPLICATE
9. Invalidar la entrada de cache del Job
10. Ack
```

**Extensiones**
- *4a.* Hay QR pero el Base64 no decodifica o el JSON no valida → se ignora el QR, se sigue por texto/OCR, se registra como anomalía (puede ser un QR falso).
- *6a.* Tesseract devuelve texto vacío o basura → `Job` queda en `EXTRACTED` con todos los campos nulos y el formulario sale vacío. **No es un fallo del sistema**: el usuario carga a mano y el archivo queda igual de guardado. Fallar acá sería perder el documento.
- *\*a.* Excepción técnica (storage caído, OOM) → `FAILED`, reintento automático.

**Criterios de aceptación**
1. Comprobante electrónico con QR legible → los 7 campos del QR, correctos, 100% de los casos.
2. PDF con QR en cualquiera de las cuatro rotaciones → detectado.
3. Comprobante sin QR → degrada a OCR sin romperse.
4. Archivo que no es una factura → `EXTRACTED` con campos vacíos, nunca se pierde el archivo.

---

#### CU-09 — Revisar y corregir los datos extraídos

**Actor:** Usuario · **Precondición:** `Job` en `EXTRACTED`

**Flujo principal**
1. El sistema muestra un formulario con todos los campos precargados.
2. **Cada campo indica su origen**: ✓ verde = QR (dato oficial), ~ ámbar = leído del texto, ? gris = OCR de baja confianza o vacío.
3. El usuario corrige lo que haga falta.
4. Al guardar, por cada campo modificado se persiste un `FieldCorrection` con el valor original y el corregido.
5. `Job` → `CONFIRMED`.

**Reglas**
- **Los campos de origen QR se muestran como solo lectura por defecto**, con un "editar igual" detrás de un clic. Son datos oficiales de ARCA: si el usuario los pisa, casi siempre es un error suyo. Pero no se bloquean del todo, porque un QR dañado existe.
- La validación de CUIT corre en el cliente, en vivo.
- Si `neto + IVA + percepciones ≠ total`, se advierte sin bloquear.

**Criterio de aceptación:** un usuario que recibe un formulario con los campos del QR correctos confirma un comprobante en menos de 10 segundos y sin tocar el teclado.

> Este criterio es el verdadero producto. Todo lo demás es infraestructura para llegar acá.

---

#### CU-13 — Exportar un rango a CSV

**Actor:** Usuario

**Flujo principal**
1. El usuario elige un rango de fechas (por fecha de emisión, no de carga).
2. El sistema junta los `Job` en `CONFIRMED` de ese usuario en ese rango.
3. Genera el CSV y lo entrega como descarga.

**Reglas de formato — las que arruinan el archivo si se ignoran**
- **Separador:** `;`. Excel en configuración regional argentina interpreta `,` como decimal, y un CSV con comas se abre todo en una columna.
- **Encoding:** UTF-8 **con BOM**. Sin BOM, Excel rompe los acentos y las eñes.
- **Decimales:** coma. `12100,50`.
- **Fechas:** `dd/mm/yyyy`.
- **Notas de crédito en negativo.** Si no, el total del período está mal.
- Una columna `cae` y una `tipo_comprobante` con el código numérico, para que sea importable a software contable.

**Criterio de aceptación:** el CSV se abre en Excel y en Google Sheets con los montos reconocidos como números (alineados a la derecha, sumables) y los acentos correctos, sin tocar nada.

> Probalo con Excel real. Es el detalle que separa un export que sirve de uno que el usuario tiene que arreglar a mano — y arreglarlo a mano es exactamente el problema que el proyecto dice resolver.

### 2.4 Reglas de negocio transversales

| # | Regla | Dónde vive |
|---|---|---|
| RN-01 | Un comprobante se identifica por `(cuit_emisor, tipo, pto_vta, nro)`. El CAE es único | Índice único en DB |
| RN-02 | Un archivo ya subido por el mismo usuario no se procesa de nuevo | `content_hash` + `user_id`, único |
| RN-03 | Un dato de origen QR tiene prioridad sobre cualquier otro | Fusión del pipeline |
| RN-04 | Las notas de crédito restan | Enum `TipoComprobante.signo` |
| RN-05 | Solo los `CONFIRMED` se exportan | Query del export |
| RN-06 | Un archivo subido nunca se pierde, pase lo que pase con la extracción | El fallo de OCR no es fallo del Job |
| RN-07 | Un usuario solo ve lo suyo | Filtro por `user_id` en **todas** las queries |
| RN-08 | Toda corrección del usuario se registra | `FieldCorrection` |
| RN-09 | Un `Job` solo se procesa desde `PENDING`, y se toma con un `UPDATE` condicional | Listener del worker |
| RN-10 | Todo camino de reproceso devuelve el `Job` a `PENDING` antes de republicar | DLQ, reaper, reintento manual |
| RN-11 | Un comprobante ya cargado por el mismo usuario no se carga dos veces. La identidad es `(cuit, tipo, pto_vta, nro)`, no el hash del archivo | Índice único parcial + chequeo en la app |
| RN-12 | Un duplicado no pierde el archivo: el `Job` queda en `DUPLICATE` apuntando al original | `duplicate_of_job_id` |

> **RN-07 merece un test propio.** El bug más común y más grave de un sistema multiusuario es el `GET /jobs/{id}` que no filtra por dueño. Escribí el test que intenta leer el job de otro usuario y espera un 404.

---

## 3. Alcance

### 3.1 El principio

> **Un MVP que hace poco y lo hace perfecto es mejor portfolio que uno que hace mucho con 70% de acierto.**

El QR hace que "perfecto" sea alcanzable para el caso principal. Aprovechalo: achicá el alcance funcional y subí la vara de calidad.

### 3.2 Dentro de v1

| Área | Alcance |
|---|---|
| Auth | Email + password. Sin roles, sin recuperación de contraseña, sin verificación de mail |
| Ingesta | Un archivo por vez. PDF, JPG, PNG. ≤ 10 MB. Mobile-first |
| Extracción | **QR → texto PDF → OCR**, con fusión por confianza y origen por campo |
| Campos | Los 7 del QR + razón social, neto, IVA total, condición IVA del receptor |
| Revisión | Formulario editable con indicador de origen por campo |
| Historial | Lista paginada, filtro por fecha y estado |
| Export | CSV por rango de fechas, formato Excel-AR |
| Archivo | Descarga del original |
| Confiabilidad | Reintentos, DLQ, deduplicación |

### 3.3 Fuera de v1, con el motivo

| Fuera | Por qué | Cuándo |
|---|---|---|
| **IVA por alícuota** | Requiere parsear la tabla de alícuotas del pie. Alto costo, solo le sirve al perfil (B) | v2 |
| **Percepciones** | Ídem | v2 |
| **Ítems de línea** | Parseo de tablas en imagen. Muy caro, bajo retorno | Quizás nunca |
| **Constatación de CAE** | Barato con el QR, pero necesita certificado y clave fiscal en ARCA: fricción de setup que puede comerse una semana | v1.1 — **candidato fuerte a adelantarse si el setup sale rápido** |
| **Carga por lote / ZIP** | Es `for` sobre CU-03. Agrega UI, no aprendizaje | v1.1 |
| **Ingesta por mail** | Necesita infra de mail entrante | v2 |
| **Multi-tenant / contador** | Otro producto | v2 |
| **Modelo de ML** | `FieldCorrection` se guarda desde el día 1 para habilitarlo. No se usa en v1 | Futuro |
| **Export a formato contable** (Tango, Xubio) | Cada uno tiene su formato. Trabajo de integración, no de arquitectura | v2 |

### 3.4 Criterios de aceptación del MVP

Reemplazan a los del README, que estaban calibrados para un mundo sin QR.

1. Un PDF de factura electrónica con QR legible → los 7 campos del QR correctos, **100%** de los casos de un set de al menos 20 comprobantes reales variados.
2. Una **foto de celular** de una factura electrónica impresa, con luz razonable → QR detectado en **≥ 85%** de los casos.
3. Un comprobante **sin** QR → degrada a OCR, no se rompe, el usuario puede completar a mano.
4. El archivo original siempre es recuperable, sin importar cómo haya salido la extracción.
5. El CSV abre en Excel con montos numéricos y acentos correctos, sin intervención.
6. Un usuario no puede acceder a datos de otro — con test automatizado que lo demuestre.
7. El sistema sobrevive a que el worker se caiga a mitad de un procesamiento: el mensaje se reprocesa y no se duplica el resultado.

El (7) es el criterio que justifica toda la mensajería. Escribí el test que mata el worker a propósito.

### 3.5 Riesgos

| Riesgo | Impacto | Mitigación |
|---|---|---|
| El QR no se detecta en fotos de celular con ángulo o reflejo | Alto — es el pilar | Probar rotaciones, binarizar antes de buscar, y en el frontend una guía visual de encuadre. Medir la tasa de detección como métrica de primera clase |
| Falta de comprobantes reales variados para probar | Alto, silencioso | Juntar un set desde el día 1. Tus propias facturas, las de conocidos. 20 comprobantes variados valen más que dos semanas de código |
| Tesseract en español anda peor de lo esperado | Medio — ya no es el pilar | Es fallback. El usuario completa a mano |
| Setup de certificados ARCA se come días | Medio | Timeboxear a 2 días. Si no sale, a v1.1 |
| Alcance que crece hacia el perfil (B) | **Alto** | Es el riesgo más probable. Releer 3.1 cuando aparezcan las ganas |
| Datos fiscales de terceros en el storage | Bajo técnico, real legal | Ley 25.326. Storage privado, URLs firmadas con vencimiento, nada de buckets públicos. Una línea en el README |

---

## 4. Arquitectura, patrones y tecnologías

### 4.1 La forma general

La arquitectura del README (API + cola + worker + Postgres + Redis + object storage) **es la correcta** para este problema. No la cambies. Lo que cambia es el interior del worker y la justificación de algunas piezas.

La decisión de arrancar síncrono y migrar a asíncrono en la Fase 2 es acertada y vale la pena defenderla: el OCR es una operación de segundos a decenas de segundos, hacerla dentro del request HTTP es insostenible, y el camino *sync primero* demuestra que se entiende **por qué** se introduce la cola en vez de ponerla porque sí.

### 4.2 El núcleo: el pipeline de extracción

Acá va el diseño que más valor tiene y el que más se nota en una entrevista.

**Patrón: Strategy + Chain of Responsibility, con fusión por confianza.**

```java
public interface ExtractionStrategy {
    boolean supports(SourceType sourceType);
    ExtractionResult extract(DocumentContent content);
    int priority();          // QR=100, PDF_TEXT=50, OCR=10
}

// Implementaciones
QrExtractionStrategy        // ZXing + Base64 + JSON
PdfTextExtractionStrategy   // PDFBox + reglas
OcrExtractionStrategy       // OpenCV + Tess4J + las mismas reglas
```

```java
public record ExtractedField<T>(
    T value,
    double confidence,
    FieldSource source        // QR | PDF_TEXT | OCR | USER
) {}
```

El orquestador corre las estrategias en orden de prioridad, acumula resultados, y al final cada campo se queda con el valor de mayor confianza. Tres consecuencias que valen oro:

1. **Agregar una estrategia nueva no toca el orquestador.** ¿PaddleOCR en v2? Una clase más, una línea de registro. Open/Closed de verdad, no de ejemplo de libro.
2. **Cada estrategia se testea sola**, con un archivo de entrada y un resultado esperado.
3. **La verificación cruzada sale gratis**, porque se tienen los valores de todas las fuentes, no solo el ganador.

> Las reglas de parseo (regex de CUIT, de montos, de fechas) viven en un componente aparte que recibe texto plano, **compartido entre `PdfTextExtractionStrategy` y `OcrExtractionStrategy`**. No las dupliques. Es la parte del código que más va a cambiar.

### 4.3 Patrones que este problema justifica

| Patrón | Qué problema concreto resuelve acá |
|---|---|
| **Hexagonal / Ports & Adapters** | Tenés tres adaptadores de extracción, dos de storage (local / S3-compatible), uno de mensajería. El dominio — parsear una factura — no debería saber de ninguno. Es de los pocos proyectos donde hexagonal se gana el lugar en vez de ser ceremonia |
| **Strategy** | Las tres estrategias de extracción. Ver 4.2 |
| **Chain of Responsibility** | El orden en cascada con corte temprano |
| **Value Object** | `Cuit` (con dígito verificador), `Importe` (BigDecimal + moneda, **nunca `double`**), `NumeroComprobante` (pto_vta + nro), `Cae`. Imposible construirlos inválidos |
| **State machine explícita** | Los estados del `Job`. Las transiciones válidas en un solo lugar, no en `if`s repartidos. `PENDING→PROCESSING→EXTRACTED→CONFIRMED` y los caminos de error. **`PENDING` es el único estado procesable**, así que todo reproceso (DLQ, reaper, reintento manual) pasa por ahí: una sola puerta de entrada en vez de un camino por cada origen |
| **Transactional Outbox** | **El que falta en el README.** Hoy el flujo es: persistir `Job` → publicar a RabbitMQ. Si el broker está caído o el proceso muere entre las dos, queda un `Job` en `PENDING` que nadie va a procesar nunca. El outbox lo arregla: escribís el evento en una tabla **en la misma transacción** que el `Job`, y un publisher aparte la vacía. **Es el mejor ADR que podés escribir en este proyecto** |
| **Idempotent Consumer** | At-least-once es un hecho de RabbitMQ, no una opción, y el outbox puede publicar dos veces. El worker toma el job con un `UPDATE ... WHERE status='PENDING'` atómico: 1 fila → procesa, 0 filas → ack y descarta. **Compare-and-set, no `SELECT` y después `UPDATE`** — entre las dos sentencias hay una ventana en la que dos réplicas arrancan el mismo job. Reforzalo con un índice único sobre `eventId` procesado |
| **Cache-aside** | El polling del frontend sobre `GET /jobs/{id}`. Invalidar borrando. Ya está bien en el README |
| **DLQ + retry con backoff** | Ya está bien en el README |
| **Repository** | Lo de siempre, vía Spring Data |

### 4.4 Patrones que NO uses — y por qué decirlo importa

En una entrevista, explicar por qué **descartaste** un patrón demuestra más criterio que haberlo implementado.

| Patrón | Por qué no |
|---|---|
| **CQRS** | Las lecturas y escrituras tienen el mismo modelo y el volumen es trivial. Redis ya cubre la única lectura caliente |
| **Event Sourcing** | El estado actual del `Job` es todo lo que importa. La auditoría ya la da `FieldCorrection` |
| **Saga** | No hay transacción distribuida: un solo servicio escribe en Postgres. El README ya lo decidió bien (DB compartida, sin evento de respuesta) |
| **Microservicios de verdad** | Son dos deployables sobre **una base de datos compartida**. Eso es un monolito distribuido, y está bien: la separación acá es por perfil de recursos (el worker necesita CPU y Tesseract, la API no), no por bounded context. Decilo así y es una fortaleza; dejá que te lo descubran y es una debilidad |
| **Kubernetes** | Docker Compose para un proyecto de portfolio. K8s sería tiempo gastado en YAML en vez de en el dominio |

> El último punto vale la pena ponerlo en un ADR. *"Dos servicios, una base de datos: por qué esto no es microservicios y por qué está bien."*

### 4.5 Ajustes al modelo de datos

Sobre el esquema del README:

**`ExtractedInvoiceData` — agregar:**

```
tipo_comprobante_codigo   -- int, código ARCA (1, 6, 11...), no un enum propio
cae                       -- ya está, pero ahora se llena siempre que haya QR
tipo_cod_aut              -- "E" (CAE) | "A" (CAEA)
moneda                    -- ya está
cotizacion                -- del QR. Si moneda != ARS importa
doc_receptor_tipo         -- del QR (80=CUIT, 86=CUIL, 96=DNI, 99=sin identificar)
doc_receptor_nro          -- del QR
qr_payload_raw            -- el JSON crudo del QR, igual que raw_ocr_text
field_sources             -- jsonb {"total":"QR","razon_social":"OCR",...}
```

`field_sources` es el complemento de `field_confidence` y es lo que permite el indicador visual del CU-09.

**`Document` — agregar:**

```
qr_detected               -- boolean. Métrica de primera clase
```

**Precisión decimal:** todos los montos `NUMERIC(15,2)` en Postgres y `BigDecimal` en Java. Nunca `float`/`double`/`REAL`. El QR especifica hasta 13 enteros y 2 decimales para el importe, y hasta 6 decimales para la cotización — `NUMERIC(19,6)` para `cotizacion`.

**Índices que vas a necesitar:**

```sql
UNIQUE (user_id, content_hash)                              -- RN-02
UNIQUE (issuer_cuit, invoice_type, point_of_sale, invoice_number)  -- RN-01
INDEX  (user_id, status, issue_date)                        -- historial y export
```

### 4.6 Stack

El del README está bien elegido. Los cambios:

**Agregar:**

| Qué | Para qué |
|---|---|
| **ZXing** (`com.google.zxing:core` + `javase`) | Detección y decodificación del QR. **La dependencia más importante del proyecto** |
| **Apache PDFBox** `PDFRenderer` | Ya está en el stack para texto; se usa también para rasterizar la página y buscarle el QR |
| **Jackson** | Parsear el JSON del QR. Ya viene con Spring Boot |
| **Testcontainers** | Postgres, RabbitMQ y el almacenamiento de objetos reales en los tests de integración. Para un proyecto cuyo argumento es "sé manejar infraestructura asíncrona", testear contra H2 y mocks contradice el argumento |
| **OpenCSV** o **Commons CSV** | Ya estaba. Que escriba el BOM UTF-8 y el separador `;` |

**Reconsiderar:**

- **OpenCV** (`org.openpnp:opencv`) pasa de crítico a secundario: sirve para mejorar la detección del QR y para el OCR de fallback. Si da problemas de portabilidad, **no bloquea el MVP**. Antes sí lo hacía.
- **Tesseract** baja de "motor del sistema" a "estrategia de último recurso". Instalá `tesseract-ocr-spa` igual, pero no le dediques la semana 3 entera.

**Sin cambios y bien elegido:** Java 21, Postgres 16, Flyway, RabbitMQ, Redis, almacenamiento S3-compatible, Micrometer + Prometheus + Grafana, React + Vite, Docker Compose, GitHub Actions.

> **Spring Boot pasó de 3.3 a 4.1.1** al armar el proyecto: Initializr ya no ofrece la línea 3.x. Ver §5.

### 4.7 Observabilidad: qué medir

Las métricas del README son genéricas. Estas cuentan la historia del dominio:

| Métrica | Tipo | Por qué |
|---|---|---|
| `invoice.qr.detection.rate` | Counter con tag `detected` | **La métrica más importante del sistema.** Es el indicador de salud del pipeline entero |
| `invoice.extraction.source` | Counter con tags `field`, `source` | Qué proporción de cada campo viene de QR vs OCR. Dice exactamente dónde invertir |
| `invoice.field.correction.rate` | Counter con tag `field` | Qué campo corrige más el usuario = qué regla de parseo está peor |
| `invoice.job.processing.time` | Timer con tag `strategy` | Separado por estrategia: el QR debe ser milisegundos, el OCR segundos |
| `invoice.ocr.confidence` | DistributionSummary | Ya estaba |
| `invoice.job.outcome` | Counter con tag `status` | Ya estaba |

> Un dashboard de Grafana con "tasa de detección de QR" y "origen de cada campo" es infinitamente más interesante de mostrar que uno con uso de CPU. Habla del problema, no de la máquina.

---

## 5. Estado de aplicación

### Ya aplicado al README y a los diagramas

- [x] **Sección 1:** subsección "Por qué no alcanza con Mis Comprobantes de ARCA" (§1.3)
- [x] **Sección 3 (Alcance):** criterios de aceptación reemplazados por los de §3.4 — el "70%" ya no corresponde; IVA por alícuota y percepciones explícitamente fuera de v1
- [x] **Sección 4 (Arquitectura):** responsabilidades del worker descritas como cascada
- [x] **Sección 5 (Esquema):** campos de §4.5, `OutboxEvent`, índices, máquina de estados del `Job` con la columna "¿procesable?"
- [x] **Sección 6:** renombrada a "Extracción de datos". QR primero, OCR como una de tres estrategias
- [x] **Sección 7 (Stack):** ZXing, Testcontainers, reglas del CSV, métricas de dominio
- [x] **Sección 8 (Eventos):** transactional outbox, claim atómico, reaper de jobs colgados
- [x] **Sección 10 (Roadmap):** la estrategia QR pasa a la semana 2; el OCR baja a la 5
- [x] **Diagramas:** ambos regenerados — el de componentes con el outbox y las tres estrategias en el worker, el de secuencia con la cascada y el relay

### Decidido durante la implementación

Cosas que este documento no podía anticipar y se resolvieron al escribir el código. Quedan acá para que el razonamiento no se pierda.

| Decisión | Por qué |
|---|---|
| **Spring Boot 4.1.1**, no 3.3 | Initializr ya no ofrece la línea 3.x. Las librerías pesadas del proyecto (PDFBox, ZXing, Tess4J) son independientes de Spring, así que el salto de major casi no las toca |
| **Java 21**, no 25 | 25 es la LTS actual, pero Tess4J usa JNA y OpenCV usa JNI, y desde Java 24 ([JEP 472](https://openjdk.org/jeps/472)) el uso de JNI emite warnings. No rompe, pero es ruido constante justo donde vas a estar depurando |
| **Módulo `extraction` propio**, fuera del worker | En Fase 1 lo usa la API y en Fase 2 el worker. Afuera de los dos, pasar de síncrono a asíncrono es agregar un deployable, no mover código |
| **`shared-events` se pospone** a la Fase 2 | Quedaba vacío hasta que exista la cola, igual que `ocr-worker-service`. Era una inconsistencia tenerlo |
| **Adobe S3Mock** en lugar de MinIO | Las imágenes de MinIO dejaron de ser públicas y LocalStack pide token de licencia. S3Mock es Apache 2.0. El código no cambia: va por el SDK de S3 igual |
| **Postgres en el puerto 5433** | El 5432 suele estar ocupado por una instalación nativa, y con los dos escuchando la app se conecta a la base equivocada |
| **`BIGINT` para CUIT y CAE**, `VARCHAR` en vez de `CHAR` | Surgió de validar los mapeos JPA. Postgres desaconseja `char(n)`, y once o catorce dígitos entran holgados en un `long` |
| **Hibernate en `shared-domain`**, scope `provided` | Las columnas JSONB necesitan `@JdbcTypeCode`, que es de Hibernate y no de JPA. Es un retroceso respecto de "acá no entra ningún framework" y está documentado en el pom con las alternativas descartadas |
| **Lombok acotado** | `@Getter`/`@Setter`/`@NoArgsConstructor` sí; `@Data`, `@EqualsAndHashCode` y `@ToString` prohibidos sobre entidades. Ver "Convenciones de código" en el README |
| **Idioma: dominio en español, técnico y prosa en inglés** | Los términos de ARCA son sustantivos propios; traducirlos da nombres peores y rompe el vínculo con la tabla oficial |

### Pendiente: escribir los ADRs

Escribilos a medida que implementás cada cosa, no al final — si se posterga se pierde el razonamiento, que es justamente lo que se evalúa en una entrevista.

| ADR | Qué decide |
|---|---|
| `004-qr-first-ocr-fallback.md` | **El más valioso.** Por qué el QR va primero y el OCR queda de respaldo |
| `005-transactional-outbox.md` | Por qué el evento se escribe en una tabla dentro de la transacción del `Job` en vez de publicarse directo |
| `006-dos-servicios-una-db.md` | Por qué dos deployables sobre una DB compartida no son microservicios, y por qué está bien |
| `007-claim-atomico-y-reentradas.md` | Por qué el worker toma el job con un `UPDATE` condicional y por qué todo reproceso pasa por `PENDING` |

---

## Fuentes

- [Especificaciones del QR incluido en las facturas electrónicas (ARCA, PDF oficial)](https://www.afip.gob.ar/fe/qr/documentos/QRespecificaciones.pdf)
- [RG 4892/2020 — obligatoriedad del código QR](http://biblioteca.afip.gob.ar/dcp/REAG01004892_2020_12_22)
- [Tabla oficial de tipos de comprobante (.xls)](https://www.afip.gob.ar/fe/documentos/TABLACOMPROBANTES.xls)
- [Tablas del sistema de Factura Electrónica](https://www.afip.gob.ar/fe/ayuda/tablas.asp)
- [RG 4597/2019 — Libro IVA Digital](https://www.consejosalta.org.ar/wp-content/uploads/RG-4597-2019.pdf)
- [Mis Comprobantes: consulta y descarga de hasta 365 días](https://contadoresenred.com/mis-comprobantes-permite-consultar-y-descargar-en-excel-periodos-de-hasta-365-dias/)
- [Mis Comprobantes — información general (CPCE Salta, PDF)](https://www.consejosalta.org.ar/wp-content/uploads/INFO.-GRAL.-MIS-COMPROBANTES.pdf)
- [Limitaciones de los comprobantes recibidos y ausencia de API](https://yo-facturo.com/docs/accounting/received-invoices/)
- [Constatación de comprobantes y validación de CAE](https://yo-facturo.com/blog/como-validar-consultar-cae-afip/)
- [Códigos de Factura de Crédito Electrónica MiPyME](https://servicioscf.afip.gob.ar/facturadecreditoelectronica/conceptos/comprobantes.asp)
