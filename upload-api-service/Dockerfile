# Imagen de upload-api-service.
#
# El contexto de build es la RAÍZ del repositorio, no esta carpeta: es un
# proyecto multi-módulo y la compilación necesita el pom padre y los
# módulos hermanos. Desde la raíz:
#
#   docker build -f upload-api-service/Dockerfile .
#
# docker-compose.yml ya lo hace así.

# ──────────────────────────────────────────────────────────────────────
#  Etapa 1 — compilar
# ──────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build

# Se usa el wrapper y no una imagen de Maven para que el contenedor
# compile con exactamente la misma versión que tu máquina. Si las
# versiones difieren, un build que anda local puede fallar acá y perdés
# medio día averiguando por qué.
COPY mvnw .
COPY .mvn .mvn
RUN chmod +x mvnw

# Primero SOLO los poms. Esta capa se invalida únicamente cuando cambia
# una dependencia, así que editar código Java no vuelve a descargar medio
# Maven Central.
COPY pom.xml .
COPY shared-domain/pom.xml shared-domain/
COPY extraction/pom.xml extraction/
COPY upload-api-service/pom.xml upload-api-service/
RUN ./mvnw -B -q dependency:go-offline -DexcludeArtifactIds=shared-domain,extraction || true

# Ahora el código. A partir de acá sí se recompila en cada cambio.
#
# Se copian los directorios de módulo enteros y no sus src/ porque un
# módulo recién creado todavía no tiene src/ y `COPY` falla si la ruta no
# existe. Hoy le pasa a `extraction`, que tiene pom y nada más.
COPY shared-domain shared-domain
COPY extraction extraction
COPY upload-api-service upload-api-service

# Los tests ya corrieron en CI; repetirlos acá solo alarga el build de la
# imagen. Si querés que el build falle ante un test roto, sacá -DskipTests.
RUN ./mvnw -B -q clean package -DskipTests

# ──────────────────────────────────────────────────────────────────────
#  Etapa 2 — separar el jar en capas
# ──────────────────────────────────────────────────────────────────────
#
# Un jar ejecutable de Spring Boot es un solo archivo de 208 MB, de los
# cuales 104 MB son OpenCV. Copiado entero, cada cambio de una línea de
# código reescribe los 208 MB.
#
# `jarmode=tools extract --layers` lo parte en cuatro capas ordenadas de
# menos a más volátil, y Docker cachea cada una por separado: cambiar
# código propio reconstruye unos pocos MB en vez de 208.
FROM eclipse-temurin:21-jdk AS layers
WORKDIR /layers
COPY --from=build /build/upload-api-service/target/*.jar app.jar
# A un subdirectorio: `extract` exige que el destino esté vacío, y el
# directorio actual ya contiene app.jar.
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# ──────────────────────────────────────────────────────────────────────
#  Etapa 3 — runtime
# ──────────────────────────────────────────────────────────────────────
# JRE y no JDK: no hace falta un compilador para ejecutar.
FROM eclipse-temurin:21-jre
WORKDIR /app

# curl para el HEALTHCHECK y para poder diagnosticar desde adentro del
# contenedor. Son pocos MB y evitan tener que reconstruir la imagen el día
# que necesites saber qué ve el proceso.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# Usuario sin privilegios. Por defecto los contenedores corren como root,
# y si alguien escapa del proceso, lo hace siendo root.
RUN groupadd --system facturas && useradd --system --gid facturas facturas

# El orden importa: de la capa que casi nunca cambia a la que cambia en
# cada commit. Invertirlo anula el beneficio del cacheo.
COPY --from=layers --chown=facturas:facturas /layers/extracted/dependencies/ ./
COPY --from=layers --chown=facturas:facturas /layers/extracted/spring-boot-loader/ ./
COPY --from=layers --chown=facturas:facturas /layers/extracted/snapshot-dependencies/ ./
COPY --from=layers --chown=facturas:facturas /layers/extracted/application/ ./

USER facturas
EXPOSE 8080

# Contenedores: el heap por defecto se calcula sobre la memoria del host,
# no sobre el límite del contenedor, y termina en OOMKilled. Esto lo ata
# al límite real.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"

# start-period generoso: el arranque incluye Flyway y la validación de
# mapeos de Hibernate. Sin él, Docker marcaría el contenedor como
# unhealthy mientras todavía está haciendo bien su trabajo.
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=5 \
  CMD curl -fsS http://localhost:8080/actuator/health || exit 1

# `exec` para que la JVM quede como PID 1 y reciba SIGTERM directamente.
# Sin eso, el shell se come la señal y Docker termina matando el proceso
# a los 10 segundos en vez de dejarlo cerrar prolijo.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
