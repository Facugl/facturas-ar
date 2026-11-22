package ar.facturas.upload.adapters.in.rest;

import ar.facturas.upload.application.port.CurrentUser;
import ar.facturas.upload.application.usecase.ExportComprobantes;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

@RestController
@RequestMapping("/export")
@RequiredArgsConstructor
public class ExportController {

    private final ExportComprobantes export;
    private final CurrentUser currentUser;

    /**
     * The confirmed comprobantes of a period, as a CSV download.
     *
     * <p>Dates come in as ISO ({@code 2026-03-01}) because that is what a
     * client sends and what an API should take. They go out of the file in
     * {@code dd/MM/yyyy}, because that is what Excel here reads as a date.
     * The two formats are not in conflict: one is a wire format, the other
     * is a presentation of it.
     *
     * <p>Always 200, including for a period with nothing in it. An empty
     * range is a true answer — the user filed nothing that month — not a
     * missing resource, and a 404 would have the client show an error for
     * a correct result. The file comes back with its header row and no
     * data rows.
     */
    @GetMapping(value = "/csv", produces = "text/csv")
    public ResponseEntity<byte[]> csv(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        var file = export.execute(currentUser.id(), from, to);

        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.filename()).build().toString())
                .body(file.content());
    }
}
