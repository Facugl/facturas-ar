package ar.facturas.domain.converter;

import ar.facturas.domain.vo.Cuit;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Maps {@link Cuit} to the {@code NUMERIC(11)} column and back.
 *
 * <p>This is what lets an entity hold a {@code Cuit} instead of a bare
 * {@code Long}. Without it the value object would stop at the service
 * layer and every read from the database would hand back an unvalidated
 * number — which defeats the point of having the type at all.
 *
 * <p>Not {@code autoApply}: applying a converter to every {@code Cuit} in
 * the model by default hides the mapping. Each column declares it.
 */
@Converter
public class CuitConverter implements AttributeConverter<Cuit, Long> {

    @Override
    public Long convertToDatabaseColumn(Cuit cuit) {
        return cuit == null ? null : cuit.value();
    }

    /**
     * A row whose CUIT does not validate means the data was written around
     * this converter, so failing loudly here is the right call: a silent
     * null would turn a data problem into a mystery further downstream.
     */
    @Override
    public Cuit convertToEntityAttribute(Long value) {
        return value == null ? null : new Cuit(value);
    }
}
