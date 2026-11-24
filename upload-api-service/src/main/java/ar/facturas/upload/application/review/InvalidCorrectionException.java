package ar.facturas.upload.application.review;

/**
 * The user sent something a field cannot hold.
 *
 * <p>A 400, not a 500, and not a silent skip. The silent skip is the
 * tempting one — accept the rest of the form and drop what did not
 * parse — and it is the worst of the three: the user watches their
 * correction be accepted and then not be there.
 *
 * <p>Carries the field and the offending text so the message can say
 * which box on the form to go back to. A validation error that does not
 * name the field is a validation error the user has to guess at.
 */
public class InvalidCorrectionException extends RuntimeException {

    private final String field;

    public InvalidCorrectionException(String field, String value, String why) {
        super("%s: %s (got \"%s\")".formatted(field, why, value));
        this.field = field;
    }

    /** For an unknown field name, where there is no value to complain about. */
    public InvalidCorrectionException(String message) {
        super(message);
        this.field = null;
    }

    public String getField() {
        return field;
    }
}
