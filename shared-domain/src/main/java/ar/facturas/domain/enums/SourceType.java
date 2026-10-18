package ar.facturas.domain.enums;

/**
 * De dónde viene el archivo subido. Decide qué estrategias de extracción
 * tiene sentido correr.
 *
 * <p>Ojo con una confusión fácil: esto <strong>no</strong> decide si se
 * busca el QR. El QR se busca siempre, incluso en un PDF con texto, porque
 * es la fuente más confiable que hay. Lo que esto decide es si además vale
 * la pena leer el texto embebido o pasar por OCR.
 */
public enum SourceType {

    /** PDF con texto embebido: PDFBox lo lee directo, sin OCR. */
    PDF_TEXT,

    /** PDF sin texto útil (es una imagen adentro de un PDF). Va por OCR. */
    PDF_SCANNED,

    /** Foto o captura. Va por OCR. */
    IMAGE;

    /** Umbral para distinguir un PDF con texto de uno escaneado. */
    public static final int MIN_CARACTERES_PDF_TEXT = 50;

    public boolean necesitaOcr() {
        return this != PDF_TEXT;
    }

    public boolean esPdf() {
        return this == PDF_TEXT || this == PDF_SCANNED;
    }
}
