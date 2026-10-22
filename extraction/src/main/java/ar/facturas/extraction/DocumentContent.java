package ar.facturas.extraction;

import ar.facturas.domain.enums.SourceType;
import ar.facturas.extraction.pdf.PdfPageRasterizer;
import ar.facturas.extraction.pdf.PdfTextReader;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * The uploaded file, plus the expensive views of it that the strategies
 * need.
 *
 * <p>Rendering a PDF page costs tens of milliseconds and every strategy
 * in the cascade wants the same images. Computing them here, once, on
 * first use, keeps that cost from being paid three times — and keeps the
 * strategies from having to coordinate about who rasterises.
 *
 * <p>Not thread-safe, and does not need to be: one instance belongs to
 * one extraction.
 */
public final class DocumentContent {

    private final byte[] bytes;
    private final String contentType;

    private List<BufferedImage> pages;   // lazy
    private boolean pagesComputed;

    private String embeddedText;         // lazy
    private boolean textComputed;

    public DocumentContent(byte[] bytes, String contentType) {
        this.bytes = Objects.requireNonNull(bytes, "bytes");
        this.contentType = contentType == null ? "" : contentType.toLowerCase();
    }

    public byte[] bytes() {
        return bytes;
    }

    public String contentType() {
        return contentType;
    }

    public boolean isPdf() {
        return contentType.contains("pdf");
    }

    /**
     * The document as images, whatever it arrived as.
     *
     * <p>A PDF is rendered page by page; a photo is itself, as one page.
     * Strategies that look at pixels do not have to care which it was.
     *
     * <p>Empty when nothing could be read — a damaged PDF, bytes that are
     * not an image. Not an exception: for the cascade that simply means
     * this strategy finds nothing and the next one tries.
     */
    public List<BufferedImage> pages() {
        if (!pagesComputed) {
            pages = computePages();
            pagesComputed = true;
        }
        return pages;
    }

    private List<BufferedImage> computePages() {
        if (isPdf()) {
            return new PdfPageRasterizer().rasterize(bytes);
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            return image == null ? List.of() : List.of(image);
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    /**
     * The text already inside the PDF, if any.
     *
     * <p>Empty for a photo, and empty for a scanned PDF, which holds a
     * picture of a page rather than a page. Computed once and shared:
     * both the classification and the text strategy want it.
     */
    public String embeddedText() {
        if (!textComputed) {
            embeddedText = isPdf() ? new PdfTextReader().read(bytes) : "";
            textComputed = true;
        }
        return embeddedText;
    }

    /**
     * What kind of source this turned out to be, now that the file has
     * been looked at.
     *
     * <p>This is the answer the upload could not give: telling a text PDF
     * from a scanned one means opening it, and only extraction does that.
     *
     * <p>It asks the document directly rather than taking a hint from
     * whichever strategies happened to run. A PDF full of text is a text
     * PDF whether or not anybody got around to reading it — tying the
     * answer to the pipeline made a perfectly good PDF report as scanned
     * simply because the text strategy was not registered yet.
     */
    public SourceType classify() {
        if (!isPdf()) {
            return SourceType.IMAGE;
        }
        return embeddedText().length() >= SourceType.MIN_CHARS_FOR_PDF_TEXT
                ? SourceType.PDF_TEXT
                : SourceType.PDF_SCANNED;
    }
}
