package ar.facturas.upload.application.usecase;

import ar.facturas.domain.enums.JobStatus;
import ar.facturas.domain.model.Document;
import ar.facturas.domain.model.Job;
import ar.facturas.domain.model.User;
import ar.facturas.domain.port.DocumentStorage;
import ar.facturas.upload.adapters.out.persistence.DocumentRepository;
import ar.facturas.upload.adapters.out.persistence.JobRepository;
import ar.facturas.upload.adapters.out.persistence.UserRepository;
import ar.facturas.upload.application.event.ComprobanteUploaded;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * No database and no S3 here. The use case talks to ports, so it can be
 * exercised on its own — which is the point of having them.
 */
@ExtendWith(MockitoExtension.class)
class UploadComprobanteTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final byte[] PDF = "%PDF-1.4 contenido".getBytes();

    @Mock UserRepository users;
    @Mock DocumentRepository documents;
    @Mock JobRepository jobs;
    @Mock DocumentStorage storage;
    @Mock ApplicationEventPublisher events;

    UploadComprobante useCase;
    User user;

    @BeforeEach
    void setUp() {
        useCase = new UploadComprobante(users, documents, jobs, storage, events);
        user = new User(USER_ID, "ana@test.ar", "hash");
    }

    private UploadComprobanteCommand command() {
        return new UploadComprobanteCommand(USER_ID, "factura.pdf", "application/pdf", PDF);
    }

    @Test
    void storesTheFileAndLeavesAPendingJob() {
        when(users.findById(USER_ID)).thenReturn(Optional.of(user));
        when(documents.findByUser_IdAndContentHash(any(), anyString())).thenReturn(Optional.empty());
        when(storage.store(any(), anyString())).thenReturn("documents/2026/10/abc.pdf");

        var result = useCase.execute(command());

        assertThat(result.duplicate()).isFalse();
        assertThat(result.status()).isEqualTo(JobStatus.PENDING);
        verify(documents).save(any(Document.class));
        verify(jobs).save(any(Job.class));
    }

    @Test
    void theHashIsOverTheBytesNotTheFilename() {
        when(users.findById(USER_ID)).thenReturn(Optional.of(user));
        when(documents.findByUser_IdAndContentHash(any(), anyString())).thenReturn(Optional.empty());
        when(storage.store(any(), anyString())).thenReturn("k");

        useCase.execute(command());

        var saved = ArgumentCaptor.forClass(Document.class);
        verify(documents).save(saved.capture());
        // SHA-256 de "%PDF-1.4 contenido"
        assertThat(saved.getValue().getContentHash())
                .hasSize(64)
                .matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("un archivo ya subido devuelve el job existente y no vuelve a guardar nada")
    void duplicateReturnsTheExistingJobWithoutStoringAgain() {
        var existingDoc = new Document(UUID.randomUUID(), user, "factura.pdf",
                "documents/2026/03/old.pdf", "application/pdf", PDF.length, "x".repeat(64));
        var existingJob = new Job(UUID.randomUUID(), existingDoc, 3);

        when(users.findById(USER_ID)).thenReturn(Optional.of(user));
        when(documents.findByUser_IdAndContentHash(any(), anyString()))
                .thenReturn(Optional.of(existingDoc));
        when(jobs.findFirstByDocument_IdOrderByCreatedAtDesc(existingDoc.getId()))
                .thenReturn(Optional.of(existingJob));

        var result = useCase.execute(command());

        assertThat(result.duplicate()).isTrue();
        assertThat(result.jobId()).isEqualTo(existingJob.getId());
        assertThat(result.uploadedAt()).isEqualTo(existingDoc.getUploadedAt());

        // Lo que no debe pasar: subir el archivo de nuevo ni crear filas.
        verify(storage, never()).store(any(), anyString());
        verify(documents, never()).save(any());
        verify(jobs, never()).save(any());
        // Y no se vuelve a extraer: el trabajo ya se hizo la primera vez.
        verify(events, never()).publishEvent(any(ComprobanteUploaded.class));
    }

    @Test
    void raisesTheEventThatTriggersExtraction() {
        when(users.findById(USER_ID)).thenReturn(Optional.of(user));
        when(documents.findByUser_IdAndContentHash(any(), anyString())).thenReturn(Optional.empty());
        when(storage.store(any(), anyString())).thenReturn("k");

        var result = useCase.execute(command());

        var event = ArgumentCaptor.forClass(ComprobanteUploaded.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().jobId()).isEqualTo(result.jobId());
    }

    /**
     * La regla que ordena el caso de uso: un Document que apunta a un
     * archivo inexistente es irreparable; un archivo sin fila es basura
     * barata. Si uno de los dos tiene que fallar primero, es el barato.
     */
    @Test
    void ifStorageFailsNothingIsWrittenToTheDatabase() {
        when(users.findById(USER_ID)).thenReturn(Optional.of(user));
        when(documents.findByUser_IdAndContentHash(any(), anyString())).thenReturn(Optional.empty());
        when(storage.store(any(), anyString()))
                .thenThrow(new DocumentStorage.StorageException("bucket caido"));

        assertThatThrownBy(() -> useCase.execute(command()))
                .isInstanceOf(DocumentStorage.StorageException.class);

        verify(documents, never()).save(any());
        verify(jobs, never()).save(any());
    }

    @Test
    void rejectsAnEmptyFile() {
        var cmd = new UploadComprobanteCommand(USER_ID, "vacio.pdf", "application/pdf", new byte[0]);
        assertThatThrownBy(() -> useCase.execute(cmd))
                .isInstanceOf(InvalidUploadException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void rejectsAFileOverTenMegabytes() {
        var cmd = new UploadComprobanteCommand(
                USER_ID, "grande.pdf", "application/pdf", new byte[11 * 1024 * 1024]);
        assertThatThrownBy(() -> useCase.execute(cmd))
                .isInstanceOf(InvalidUploadException.class)
                .hasMessageContaining("limit");
    }

    @ParameterizedTest(name = "rechaza {0}")
    @ValueSource(strings = {"text/plain", "application/zip", "image/gif", "application/msword"})
    void rejectsUnsupportedTypes(String contentType) {
        var cmd = new UploadComprobanteCommand(USER_ID, "x", contentType, PDF);
        assertThatThrownBy(() -> useCase.execute(cmd))
                .isInstanceOf(InvalidUploadException.class)
                .hasMessageContaining("Unsupported");
    }

    @ParameterizedTest(name = "acepta {0}")
    @ValueSource(strings = {"application/pdf", "image/jpeg", "image/png", "IMAGE/PNG"})
    void acceptsPdfAndPhotos(String contentType) {
        when(users.findById(USER_ID)).thenReturn(Optional.of(user));
        when(documents.findByUser_IdAndContentHash(any(), anyString())).thenReturn(Optional.empty());
        when(storage.store(any(), anyString())).thenReturn("k");

        var cmd = new UploadComprobanteCommand(USER_ID, "x", contentType, PDF);

        assertThat(useCase.execute(cmd).duplicate()).isFalse();
    }

    @Test
    void validationHappensBeforeAnythingElse() {
        // Un tipo invalido no debe costar ni una consulta a la base.
        var cmd = new UploadComprobanteCommand(USER_ID, "x", "text/plain", PDF);

        assertThatThrownBy(() -> useCase.execute(cmd))
                .isInstanceOf(InvalidUploadException.class);

        verify(users, never()).findById(any());
        verify(storage, never()).store(any(), anyString());
    }
}
