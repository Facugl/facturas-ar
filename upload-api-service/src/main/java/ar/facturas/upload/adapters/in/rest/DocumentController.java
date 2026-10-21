package ar.facturas.upload.adapters.in.rest;

import ar.facturas.upload.application.port.CurrentUser;
import ar.facturas.upload.application.usecase.InvalidUploadException;
import ar.facturas.upload.application.usecase.UploadComprobante;
import ar.facturas.upload.application.usecase.UploadComprobanteCommand;
import ar.facturas.upload.application.usecase.UploadComprobanteResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final UploadComprobante uploadComprobante;
    private final CurrentUser currentUser;

    /**
     * Uploads a comprobante for extraction.
     *
     * <p>Two success codes, and the difference matters:
     * <ul>
     *   <li><strong>202 Accepted</strong> — the file is stored and queued.
     *       Accepted rather than Created because the interesting work has
     *       not happened yet; the caller polls {@code jobId} to find out
     *       how it went.
     *   <li><strong>200 OK</strong> — this exact file was already here.
     *       The response carries the job it already had. Not a 409: a
     *       double click is not a conflict to resolve, and the caller
     *       follows the same {@code jobId} either way.
     * </ul>
     */
    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<UploadResponse> upload(@RequestParam("file") MultipartFile file) {
        UploadComprobanteResult result = uploadComprobante.execute(
                new UploadComprobanteCommand(
                        currentUser.id(),
                        file.getOriginalFilename(),
                        file.getContentType(),
                        readBytes(file)));

        return ResponseEntity
                .status(result.duplicate() ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .body(UploadResponse.from(result));
    }

    /**
     * Reading a multipart file can fail mid-stream — a dropped connection,
     * a full temp directory. Surfacing that as a 400 rather than a 500 is
     * the honest answer: the request did not arrive whole, and retrying
     * is the right move.
     */
    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new InvalidUploadException(
                    "The file could not be read: " + e.getMessage());
        }
    }
}
