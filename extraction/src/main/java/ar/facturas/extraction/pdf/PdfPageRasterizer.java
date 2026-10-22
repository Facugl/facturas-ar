package ar.facturas.extraction.pdf;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders PDF pages to images so a QR can be looked for in them.
 *
 * <p>A PDF carries its QR as a drawing, not as text, so extracting the
 * text of the page never finds it. The page has to be painted first.
 *
 * <p>The resolution matters more than it looks. A comprobante prints its
 * QR at roughly two centimetres square; rendered at the PDF's native 72
 * dpi that is about 57 pixels across, which for a dense code is barely
 * one pixel per module and will not decode. At 200 dpi the same square
 * comes out near 157 pixels, which does.
 */
public final class PdfPageRasterizer {

    /**
     * High enough for a small dense QR, low enough that an A4 page stays
     * around 1654x2339 — a few megabytes in memory, not tens.
     */
    public static final int DEFAULT_DPI = 200;

    /**
     * Only the first pages are rendered. ARCA puts the QR on the
     * comprobante itself, and a PDF with dozens of pages is an attached
     * report, not an invoice: rendering all of it would cost seconds and
     * find nothing.
     */
    public static final int MAX_PAGES = 3;

    private final int dpi;

    public PdfPageRasterizer() {
        this(DEFAULT_DPI);
    }

    public PdfPageRasterizer(int dpi) {
        this.dpi = dpi;
    }

    /**
     * @return one image per rendered page, in order. Empty when the file
     *         is not a readable PDF — damaged, encrypted, or simply not a
     *         PDF at all. Not an exception: for the cascade this just
     *         means "no QR here", and the next strategy takes over.
     */
    public List<BufferedImage> rasterize(byte[] pdf) {
        if (pdf == null || pdf.length == 0) {
            return List.of();
        }
        try (PDDocument document = Loader.loadPDF(pdf)) {
            PDFRenderer renderer = new PDFRenderer(document);
            int pages = Math.min(document.getNumberOfPages(), MAX_PAGES);
            List<BufferedImage> images = new ArrayList<>(pages);
            for (int page = 0; page < pages; page++) {
                images.add(renderer.renderImageWithDPI(page, dpi));
            }
            return images;
        } catch (IOException | RuntimeException e) {
            // RuntimeException as well: PDFBox throws unchecked for some
            // kinds of malformed file, and a broken upload must not take
            // the extraction down with it.
            return List.of();
        }
    }
}
