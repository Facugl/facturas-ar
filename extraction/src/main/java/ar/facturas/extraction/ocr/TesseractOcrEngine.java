package ar.facturas.extraction.ocr;

import net.sourceforge.tess4j.ITessAPI;
import net.sourceforge.tess4j.util.LoadLibs;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.Word;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Optional;

/**
 * Tesseract 5, through Tess4J, in this process.
 *
 * <p>Not a service, per
 * <a href="../../../../../../../docs/adr/002-tesseract-y-no-paddleocr.md">ADR 002</a>:
 * Tess4J is a JNI binding, so the whole deployment story is installing
 * {@code tesseract-ocr} in the worker's image.
 *
 * <h2>Which language, and why it is a real decision</h2>
 *
 * <p>Spanish where it is installed, English otherwise, and the reading
 * says which one it was.
 *
 * <p>The jar bundles {@code eng.traineddata} and the Windows natives, so
 * {@code eng} works everywhere with no setup. {@code spa} is not bundled
 * — it is a 15 MB binary that does not belong in a git repository — so
 * the worker's image installs {@code tesseract-ocr-spa} and a developer
 * on Windows gets English.
 *
 * <p>The bundled data has to be <strong>unpacked to disk</strong> first:
 * Tesseract takes a directory, not a classpath, so traineddata inside a
 * jar is invisible to it. That is what {@code extractTessResources} is
 * for, and forgetting it is an
 * {@code IllegalArgumentException: Specified language data does not
 * exist} that sounds like a missing file and is a missing directory.
 *
 * <p>How much that costs is smaller than it sounds, and the reason is
 * which two fields this strategy exists for. The CUIT is digits and
 * hyphens, where a language model contributes nothing. A razón social is
 * proper nouns and legal suffixes — {@code S.A.}, {@code S.R.L.} — which
 * are not dictionary words in either language. What {@code spa} actually
 * buys is the accents: {@code PANADERÍA} rather than {@code PANADERIA} or
 * {@code PANADERlA}. Worth having, not worth a binary in the repo, and
 * the review form corrects it either way.
 *
 * <p>Nothing in the tests may depend on which one ran, because CI and a
 * Windows laptop will not agree.
 *
 * <h2>Why it reports its own confidence</h2>
 *
 * <p>Tesseract gives a per-word confidence and this averages it. That
 * number is not the probability that the text is correct — it is the
 * engine's opinion of its own output — but it is the only signal
 * available for distinguishing "read a clean scan" from "guessed at a
 * dark photograph", and it is what {@code invoice.ocr.confidence} graphs.
 */
public final class TesseractOcrEngine implements OcrEngine {

    /** What the jar bundles, and the floor under everything else. */
    static final String FALLBACK_LANGUAGE = "eng";

    /** What a comprobante is actually written in. */
    static final String PREFERRED_LANGUAGE = "spa";

    /**
     * Resolved once, because the answer cannot change while the process
     * runs and asking costs a native call plus unpacking a file.
     */
    private static final Resolution RESOLUTION = resolve();

    private record Resolution(String datapath, String language, String unavailableReason) {}

    private final String datapath;
    private final String language;
    private final String unavailableReason;

    public TesseractOcrEngine() {
        this(RESOLUTION.datapath(), RESOLUTION.language(), RESOLUTION.unavailableReason());
    }

    TesseractOcrEngine(String language, String unavailableReason) {
        this(null, language, unavailableReason);
    }

    TesseractOcrEngine(String datapath, String language, String unavailableReason) {
        this.datapath = datapath;
        this.language = language;
        this.unavailableReason = unavailableReason;
    }

    /**
     * Finds out whether Tesseract runs here, from where, and in which
     * language.
     *
     * <p>Decided by actually OCR-ing a small image rather than by looking
     * for files, because the failure this guards against is a native
     * library that is present and will not link — and the only thing that
     * answers that honestly is calling it.
     *
     * <p>Two datapaths are tried in order, and the fallback matters: an
     * image that sets {@code TESSDATA_PREFIX} to the wrong place should
     * degrade to English from the bundled data, not to no OCR at all.
     * That is a deployment mistake turning into a worse result instead of
     * no result.
     */
    private static Resolution resolve() {
        String lastFailure = "no datapath could be resolved";
        for (String datapath : datapaths()) {
            for (String language : List.of(PREFERRED_LANGUAGE, FALLBACK_LANGUAGE)) {
                String failure = probe(datapath, language);
                if (failure == null) {
                    return new Resolution(datapath, language, null);
                }
                lastFailure = failure;
            }
        }
        return new Resolution(null, null, lastFailure);
    }

    /**
     * Where to look for traineddata: what the image says first, then the
     * copy bundled in the jar.
     *
     * <p>{@code TESSDATA_PREFIX} is the variable Tesseract itself
     * documents, so an image that installs {@code tesseract-ocr-spa}
     * points at {@code /usr/share/tesseract-ocr/5/tessdata} and gets
     * Spanish without this class knowing the path.
     */
    private static List<String> datapaths() {
        List<String> paths = new java.util.ArrayList<>(2);

        String fromEnvironment = System.getenv("TESSDATA_PREFIX");
        if (fromEnvironment != null && !fromEnvironment.isBlank()) {
            paths.add(fromEnvironment);
        }

        try {
            // Unpacks the jar's tessdata into a temp directory and
            // answers where. Tesseract needs a directory on disk; a
            // classpath resource is invisible to it.
            paths.add(LoadLibs.extractTessResources("tessdata").getAbsolutePath());
        } catch (Throwable t) {
            // Nothing to add. If the environment gave a path it still
            // gets its turn; if not, resolve() reports unavailable.
        }
        return paths;
    }

    /** Null when that datapath and language work; otherwise why not. */
    private static String probe(String datapath, String language) {
        try {
            // 32x32 and not 1x1: Tesseract refuses an image too small to
            // hold a glyph, and the refusal looks exactly like a missing
            // library.
            BufferedImage probe = new BufferedImage(32, 32, BufferedImage.TYPE_BYTE_GRAY);
            newTesseract(datapath, language).doOCR(probe);
            return null;
        } catch (Throwable t) {
            // Throwable and not Exception: a native that will not load
            // surfaces as UnsatisfiedLinkError, which is an Error. Same
            // reasoning as WeChatQrEngine, and for the same reason —
            // swallowing it silently is the failure mode that costs a
            // morning.
            return "%s (%s, %s)".formatted(
                    t.getClass().getSimpleName() + ": " + t.getMessage(), datapath, language);
        }
    }

    private static Tesseract newTesseract(String datapath, String language) {
        Tesseract tesseract = new Tesseract();
        tesseract.setDatapath(datapath);
        tesseract.setLanguage(language);
        // 3 is "fully automatic page segmentation, no OSD": the right one
        // for a whole receipt, which is a page of mixed blocks. Treating
        // it as a single text line — a tempting setting for a narrow
        // ticket — loses the layout that separates the issuer's block
        // from the items.
        tesseract.setPageSegMode(ITessAPI.TessPageSegMode.PSM_AUTO);
        // LSTM only. The legacy engine is faster and markedly worse, and
        // "combined" pays for both to use one.
        tesseract.setOcrEngineMode(ITessAPI.TessOcrEngineMode.OEM_LSTM_ONLY);
        return tesseract;
    }

    @Override
    public boolean available() {
        return unavailableReason == null;
    }

    @Override
    public String unavailableReason() {
        return unavailableReason;
    }

    /** Which traineddata this instance will use. Null when unavailable. */
    public String language() {
        return language;
    }

    /** Where that traineddata was found. For diagnostics. */
    public String datapath() {
        return datapath;
    }

    @Override
    public Optional<Reading> read(BufferedImage image) {
        if (!available() || image == null) {
            return Optional.empty();
        }
        try {
            Tesseract tesseract = newTesseract(datapath, language);
            String text = tesseract.doOCR(image);
            if (text == null || text.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new Reading(text.strip(), confidenceOf(tesseract, image), language));
        } catch (Throwable t) {
            // One unreadable image must not take down a cascade whose
            // whole point is that the other strategies keep going.
            return Optional.empty();
        }
    }

    /**
     * The mean word confidence, as a fraction.
     *
     * <p>Per word and not per character: Tesseract's character-level
     * numbers are noisy on a receipt, and a word is the unit a human
     * would call right or wrong.
     *
     * <p>Zero when it recognised no words at all, which is distinguishable
     * from a low score and means something different — nothing was read,
     * rather than read badly.
     */
    private static double confidenceOf(Tesseract tesseract, BufferedImage image) {
        try {
            List<Word> words = tesseract.getWords(
                    image, ITessAPI.TessPageIteratorLevel.RIL_WORD);
            if (words == null || words.isEmpty()) {
                return 0.0;
            }
            double total = words.stream().mapToDouble(Word::getConfidence).sum();
            return Math.clamp(total / words.size() / 100.0, 0.0, 1.0);
        } catch (Throwable t) {
            // The text is already in hand; losing the confidence is not
            // worth losing the reading over.
            return 0.0;
        }
    }
}
