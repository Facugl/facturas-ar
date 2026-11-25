# Imagen de ocr-worker-service.
#
# El contexto de build es la RAÍZ del repositorio, no esta carpeta: es un
# proyecto multi-módulo y la compilación necesita el pom padre y los
# módulos hermanos. docker-compose.yml ya lo hace así.
#
# Misma forma que la imagen de la API, con tres diferencias que importan:
#   - No lleva Flyway: el esquema lo migra la API ([ADR 013]).
#   - Carga los binarios nativos de OpenCV, que son el peso real de esta
#     imagen y la razón por la que es un deployable aparte ([ADR 006]).
#   - Expone el 8081, y no para atender a nadie: es Actuator.

# ──────────────────────────────────────────────────────────────────────
#  Etapa 1 — compilar
# ──────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build

COPY mvnw .
COPY .mvn .mvn
RUN chmod +x mvnw

# Primero SOLO los poms, para que editar código Java no vuelva a
# descargar medio Maven Central.
COPY pom.xml .
COPY shared-domain/pom.xml shared-domain/
COPY shared-events/pom.xml shared-events/
COPY shared-infrastructure/pom.xml shared-infrastructure/
COPY extraction/pom.xml extraction/
COPY upload-api-service/pom.xml upload-api-service/
COPY ocr-worker-service/pom.xml ocr-worker-service/
#
# SIN `|| true`: en la imagen de la API ese `|| true` escondio durante dos
# sesiones que faltaba un modulo en la lista de arriba, y compose se quedo
# con la imagen vieja sin avisar. Un modulo faltante tiene que romper aca.
RUN ./mvnw -B -q dependency:go-offline \
      -DexcludeArtifactIds=shared-domain,shared-events,shared-infrastructure,extraction,upload-api-service

# Directorios de módulo enteros: un módulo recién creado todavía no tiene
# src/ y `COPY` falla si la ruta no existe.
#
# upload-api-service entra aunque este servicio no lo use en runtime: es
# una dependencia de TEST (ver su pom), y el build del módulo la pide
# igual. Es el costo de mantener el criterio 8 bajo test automático.
COPY shared-domain shared-domain
COPY shared-events shared-events
COPY shared-infrastructure shared-infrastructure
COPY extraction extraction
COPY upload-api-service upload-api-service
COPY ocr-worker-service ocr-worker-service

# Los tests ya corrieron en CI, y los de este módulo necesitan Docker
# (Testcontainers): correrlos acá adentro pediría un daemon dentro del
# build.
RUN ./mvnw -B -q clean package -DskipTests -pl ocr-worker-service -am

# ──────────────────────────────────────────────────────────────────────
#  Etapa 2 — separar el jar en capas
# ──────────────────────────────────────────────────────────────────────
#
# Esta imagen pesa lo que pesa por OpenCV: ~46 MB de binarios nativos que
# el detector de QR de WeChat necesita ([ADR 010]). Copiado entero, cada
# cambio de una línea de código reescribiría todo.
FROM eclipse-temurin:21-jdk AS layers
WORKDIR /layers
COPY --from=build /build/ocr-worker-service/target/*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# ──────────────────────────────────────────────────────────────────────
#  Etapa 3 — runtime
# ──────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre
WORKDIR /app

# curl para el HEALTHCHECK, y Tesseract para el OCR.
#
# `tesseract-ocr` es el binario nativo que Tess4J invoca por JNI; sin él
# la cascada sigue funcionando y la estrategia de OCR se declara no
# disponible, que es distinto de "no pude leer el documento" y se ve en
# las anomalías del job.
#
# `tesseract-ocr-spa` es la parte que importa de verdad y la razón de que
# esto sea apt y no un archivo en el repo: el `spa.traineddata` son ~15 MB
# de binario que no van en git. El jar de Tess4J trae `eng`, así que sin
# este paquete el worker leería comprobantes argentinos con el modelo de
# inglés. Cuánto cuesta eso es menos de lo que parece —un CUIT son
# dígitos, y "S.A." no es palabra de diccionario en ningún idioma— pero
# los acentos sí: PANADERÍA contra PANADERIA.
#
# TESSDATA_PREFIX es cómo TesseractOcrEngine encuentra esto sin saber la
# ruta. Si apunta mal, el motor cae al `eng` empaquetado en el jar en vez
# de quedarse sin OCR: un error de deploy degrada el resultado, no lo
# elimina.
#
# OpenCV no necesita apt: bytedeco empaqueta los .so en el jar, y acá se
# compila en Linux, donde entran los de linux-x86_64 y no los de Windows
# (que cuelgan de un perfil que acá no se activa).
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      curl \
      tesseract-ocr \
      tesseract-ocr-spa \
 && rm -rf /var/lib/apt/lists/*

ENV TESSDATA_PREFIX=/usr/share/tesseract-ocr/5/tessdata

# Usuario sin privilegios. Por defecto los contenedores corren como root,
# y si alguien escapa del proceso, lo hace siendo root.
RUN groupadd --system facturas && useradd --system --gid facturas facturas

# De la capa que casi nunca cambia a la que cambia en cada commit.
COPY --from=layers --chown=facturas:facturas /layers/extracted/dependencies/ ./
COPY --from=layers --chown=facturas:facturas /layers/extracted/spring-boot-loader/ ./
COPY --from=layers --chown=facturas:facturas /layers/extracted/snapshot-dependencies/ ./
COPY --from=layers --chown=facturas:facturas /layers/extracted/application/ ./

USER facturas
EXPOSE 8081

# Contenedores: el heap por defecto se calcula sobre la memoria del host,
# no sobre el límite del contenedor, y termina en OOMKilled.
#
# 75% y no más: la extracción carga binarios nativos (OpenCV) que piden
# memoria FUERA del heap. Un MaxRAMPercentage alto deja a la JVM creyendo
# que tiene toda la memoria del contenedor y al OOM killer apareciendo
# por lo que pide OpenCV, que es el error más difícil de diagnosticar acá.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"

# start-period generoso: el arranque incluye la validación de mapeos de
# Hibernate y la carga de los binarios nativos.
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=5 \
  CMD curl -fsS http://localhost:8081/actuator/health || exit 1

# `exec` para que la JVM quede como PID 1 y reciba SIGTERM directamente.
# Importa más acá que en la API: el worker tiene que poder terminar el
# mensaje que está procesando antes de morir, o el job queda en
# PROCESSING esperando al reaper.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
