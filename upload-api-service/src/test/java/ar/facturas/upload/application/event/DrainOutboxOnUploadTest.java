package ar.facturas.upload.application.event;

import ar.facturas.upload.application.outbox.OutboxRelay;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DrainOutboxOnUploadTest {

    @Mock OutboxRelay relay;

    @InjectMocks DrainOutboxOnUpload listener;

    @Test
    void ringsTheRelay() {
        UUID jobId = UUID.randomUUID();

        listener.on(new ComprobanteUploaded(jobId));

        verify(relay).drain();
    }

    /**
     * The upload is already committed when this runs, so a transport
     * that is down must not turn a successful request into an error the
     * user sees. The event stays pending and the sweep publishes it; the
     * only cost is latency.
     *
     * <p>This was the behaviour before the catch existed too, but only
     * because Spring drops exceptions thrown in a synchronization's
     * {@code afterCompletion} phase. Resting on that meant the
     * resilience depended on which phase the framework used, and it left
     * an {@code ERROR} with a stack trace in the log on every upload
     * while the broker was down.
     */
    @Test
    @DisplayName("si el transporte esta caido, el timbre falla y la subida no se entera")
    void aTransportThatIsDownDoesNotFailTheUpload() {
        doThrow(new IllegalStateException("broker caido")).when(relay).drain();

        assertThatCode(() -> listener.on(new ComprobanteUploaded(UUID.randomUUID())))
                .doesNotThrowAnyException();
    }
}
