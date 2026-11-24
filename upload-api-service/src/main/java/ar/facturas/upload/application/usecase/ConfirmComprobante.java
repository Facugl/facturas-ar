package ar.facturas.upload.application.usecase;

import ar.facturas.domain.enums.FieldSource;
import ar.facturas.domain.enums.JobStatus;
import ar.facturas.domain.model.ExtractedInvoiceData;
import ar.facturas.domain.model.FieldCorrection;
import ar.facturas.domain.model.Job;
import ar.facturas.infrastructure.persistence.ExtractedInvoiceDataRepository;
import ar.facturas.infrastructure.persistence.FieldCorrectionRepository;
import ar.facturas.infrastructure.persistence.JobRepository;
import ar.facturas.upload.application.metrics.PipelineMetrics;
import ar.facturas.upload.application.review.CorrectableField;
import ar.facturas.upload.application.review.InvalidCorrectionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The user reviewed an extraction, fixed what was wrong, and takes the
 * rest as correct.
 *
 * <p>One call, not two. A correction without a confirmation would be a
 * draft, and this product has no drafts: the form is opened, edited and
 * submitted once. Splitting it into {@code PATCH} then {@code PUT} would
 * invent a state —"corrected but not confirmed"— that nothing needs and
 * that the job's state machine does not have.
 *
 * <h2>The server computes the diff</h2>
 *
 * <p>The request carries <em>values</em>, not claims about what changed.
 * The client says "the total is 18450.00"; it does not say "I changed the
 * total from 18000.00". That is the decision this class turns on, and it
 * buys two things that cannot be had otherwise:
 *
 * <ul>
 *   <li><strong>The audit row cannot be forged.</strong>
 *       {@code original_value} is read out of the database here, so a
 *       buggy or hostile client cannot write a history of a correction
 *       that never happened.</li>
 *   <li><strong>Nothing can be forgotten.</strong> A form that submits
 *       all nine fields every time —which is the easy thing for a
 *       frontend to do— produces exactly the corrections that differ,
 *       with no cooperation required from it.</li>
 * </ul>
 *
 * <p>The corollary is that confirming with no changes writes no rows,
 * which is what makes {@code field_corrections} a dataset about the
 * extraction rather than about how the form submits.
 *
 * <h2>What a correction does besides setting the value</h2>
 *
 * <p>It also rewrites that field's entry in {@code field_sources} to
 * {@link FieldSource#USER} and its confidence to 1.0. Not cosmetic: the
 * sources map is what paints the "this came from the QR, trust it" badge
 * on the form. Leaving it alone would mean a field the user typed by hand
 * still claiming to be official QR data — the form would be lying about
 * the one thing it exists to tell the truth about.
 *
 * <p>{@code USER} was in the {@code FieldSource} enum from the first
 * commit, documented as "beats any extraction", and until now nothing
 * ever wrote it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConfirmComprobante {

    private final JobRepository jobs;
    private final ExtractedInvoiceDataRepository extractedData;
    private final FieldCorrectionRepository corrections;
    private final PipelineMetrics metrics;

    /**
     * @param fields the values the client is submitting, keyed by the
     *               camelCase names {@code JobResponse.Fields} uses. An
     *               entry with a null value clears the field; a field not
     *               present is left exactly as it was.
     */
    public record Command(UUID jobId, UUID userId, Map<String, String> fields) {

        public Command(UUID jobId, UUID userId) {
            this(jobId, userId, Map.of());
        }
    }

    /**
     * @param corrected the fields that actually changed, which is not the
     *                  same as the fields that were submitted
     */
    public record Result(Job job, ExtractedInvoiceData data, List<String> corrected) {
    }

    /**
     * @return empty when there is no such job <em>for this user</em> —
     *         the two cases are deliberately indistinguishable, so the
     *         caller answers 404 either way and a stranger cannot learn
     *         that an id exists
     * @throws JobNotConfirmableException  when the job is not in a state
     *                                     that can be confirmed
     * @throws InvalidCorrectionException  when a submitted value is not
     *                                     one its field can hold
     */
    @Transactional
    public Optional<Result> execute(Command command) {
        Optional<Job> found = jobs.findById(command.jobId())
                .filter(job -> job.getDocument().getUser().getId().equals(command.userId()));
        if (found.isEmpty()) {
            return Optional.empty();
        }

        Job job = found.get();
        if (!job.getStatus().canTransitionTo(JobStatus.CONFIRMED)) {
            // Asked before moving, so the caller gets a 409 rather than
            // the IllegalStateException the entity would throw. The rule
            // itself stays in JobStatus; this only reads it.
            throw new JobNotConfirmableException(job.getStatus());
        }

        // Every value parsed before any of them is applied, so a form
        // with one bad field changes nothing at all rather than applying
        // the first five corrections and then failing.
        Map<CorrectableField, Object> submitted = parse(command.fields());

        List<String> corrected = submitted.isEmpty()
                ? List.of()
                : correct(job, submitted);

        job.confirm();
        return Optional.of(new Result(
                jobs.save(job),
                extractedData.findById(job.getId()).orElse(null),
                corrected));
    }

    /**
     * Turns the request's keys into fields, refusing an unknown one.
     *
     * <p>Named in the error rather than ignored. A client sending
     * {@code issuerBussinessName} would otherwise watch its correction be
     * accepted with a 200 and then not be there, which is the kind of bug
     * that gets blamed on the database.
     */
    private Map<CorrectableField, Object> parse(Map<String, String> fields) {
        Map<CorrectableField, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            CorrectableField field = CorrectableField.byJsonName(entry.getKey())
                    .orElseThrow(() -> new InvalidCorrectionException(
                            "Unknown field \"%s\". Correctable fields are: %s"
                                    .formatted(entry.getKey(),
                                               String.join(", ", CorrectableField.jsonNames()))));
            out.put(field, field.parse(entry.getValue()));
        }
        return out;
    }

    /**
     * Applies each submitted value and records the ones that moved.
     *
     * <p>Nothing here can fail: every value arrived already parsed.
     *
     * <p>Whether a field changed is decided by comparing the canonical
     * string before and after the write, never by comparing the request
     * against the entity. That is what makes the no-op case reliable:
     * the two sides of the comparison come from the same reader, so a
     * difference means a difference in the stored value and not a
     * difference in formatting.
     */
    private List<String> correct(Job job, Map<CorrectableField, Object> submitted) {
        ExtractedInvoiceData data = extractedData.findById(job.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "Job " + job.getId() + " is EXTRACTED with no extracted_invoice_data "
                        + "row; corrections have nothing to be applied to"));

        Map<String, String> sources = new HashMap<>(
                data.getFieldSources() == null ? Map.of() : data.getFieldSources());
        Map<String, Double> confidence = new HashMap<>(
                data.getFieldConfidence() == null ? Map.of() : data.getFieldConfidence());

        List<String> corrected = new ArrayList<>();
        for (Map.Entry<CorrectableField, Object> entry : submitted.entrySet()) {
            CorrectableField field = entry.getKey();

            String before = field.currentValue(data);
            field.set(data, entry.getValue());
            String after = field.currentValue(data);

            if (java.util.Objects.equals(before, after)) {
                continue;
            }

            corrections.save(new FieldCorrection(
                    UUID.randomUUID(), job, field.key(), before, after));

            // The badge has to stop saying QR. See the class javadoc.
            sources.put(field.key(), FieldSource.USER.name());
            confidence.put(field.key(), FieldSource.USER.baseConfidence());

            corrected.add(field.jsonName());
            metrics.fieldCorrected(field.key());
        }

        if (!corrected.isEmpty()) {
            // Replaced wholesale rather than mutated in place: these are
            // JSON columns, and Hibernate only writes one back if the
            // reference it holds is a different object.
            data.setFieldSources(sources);
            data.setFieldConfidence(confidence);
            extractedData.save(data);
            log.info("Job {} confirmed with {} corrections: {}",
                    job.getId(), corrected.size(), corrected);
        }
        return corrected;
    }
}
