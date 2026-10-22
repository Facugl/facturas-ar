# 002 — Tesseract y no PaddleOCR

**Estado:** aceptada · pendiente de implementar
**Fecha:** 2026-10-17 · revisada 2026-10-22

## Contexto

La estrategia de OCR necesita un motor. Los dos candidatos razonables
eran Tesseract 5 (vía Tess4J) y PaddleOCR.

PaddleOCR tiene mejor precisión reportada, sobre todo en texto de baja
calidad. Pero es Python primero: usarlo desde Java significa levantar un
microservicio aparte, con su propia imagen, su propio despliegue y su
propio protocolo.

## Decisión

**Tesseract 5 vía Tess4J**, dentro del mismo proceso del worker.

## Consecuencias

**Una dependencia, no un servicio.** Tess4J es un binding JNI: se instala
`tesseract-ocr` en la imagen del worker y listo.

**La precisión importa menos de lo que parecía.** Esta decisión se tomó
cuando el OCR era el motor del sistema. Después de
[004](004-qr-primero-ocr-de-respaldo.md) pasó a ser el último recurso,
que corre solo sobre los campos que el QR y el texto embebido no
trajeron. La diferencia de precisión entre los dos motores pesa mucho
menos sobre un campo que sobre doce.

**Arrastra binarios nativos.** OpenCV y Tesseract suman unos 400 MB a la
imagen. Es el costo real, y queda contenido en el worker.

**Warnings de JNI en Java 24+.** Motivo por el que el proyecto va en
Java 21 y no en 25.

## Cuándo habría que revisarla

Si la razón social sale mal lo suficientemente seguido como para que
moleste. El dataset de `FieldCorrection` es exactamente lo que va a
responder eso con datos en vez de con intuición.

## Alternativas descartadas

**PaddleOCR como microservicio.** Mejor motor, pero un servicio más que
desplegar y mantener para mejorar un campo.

**Un servicio de OCR en la nube.** Costo por documento, dependencia
externa y datos fiscales de terceros saliendo del sistema.
