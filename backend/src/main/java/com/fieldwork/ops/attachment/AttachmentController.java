package com.fieldwork.ops.attachment;

import com.fieldwork.ops.common.security.SecurityUtils;
import com.fieldwork.ops.workorder.dto.AttachmentResponse;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Attachment REST API. Thin by design: every endpoint resolves the
 * principal once via {@link SecurityUtils} and delegates to
 * {@link AttachmentService}.
 *
 * <p>RBAC is enforced in two layers: {@code @PreAuthorize} role gates
 * here, plus ticket-ownership checks inside the service (a technician
 * may only touch attachments on assigned tickets, a requester only on
 * their own). The ticket-detail endpoint in
 * {@code WorkOrderController} embeds the attachment list; the raw
 * bytes are only ever served from the download endpoint below.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AttachmentController {

    private final AttachmentService attachmentService;

    /**
     * Uploads a file to a ticket. The uploader is the authenticated
     * principal; only tickets the principal may access accept uploads.
     */
    @PostMapping(value = "/work-orders/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('TECHNICIAN', 'DISPATCHER', 'REQUESTER')")
    public ResponseEntity<AttachmentResponse> upload(
            @PathVariable UUID id, @RequestParam("file") MultipartFile file) {
        AttachmentResponse created =
                attachmentService.upload(id, file, SecurityUtils.requireCurrentUser());
        return ResponseEntity.created(URI.create("/api/v1/attachments/" + created.id()))
                .body(created);
    }

    /**
     * Streams an attachment's bytes with the stored content type and an
     * {@code attachment} content-disposition so browsers download it
     * rather than render it inline.
     */
    @GetMapping("/attachments/{attachmentId}/download")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<byte[]> download(@PathVariable UUID attachmentId) {
        AttachmentDownload download =
                attachmentService.download(attachmentId, SecurityUtils.requireCurrentUser());
        MediaType contentType;
        try {
            contentType = MediaType.parseMediaType(download.contentType());
        } catch (InvalidMediaTypeException e) {
            // Cannot happen for uploads (the content type is allowlisted
            // on the way in); degrade to a generic binary download.
            contentType = MediaType.APPLICATION_OCTET_STREAM;
        }
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(download.fileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(contentType)
                .contentLength(download.content().length)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(download.content());
    }

    /**
     * Deletes an attachment: the metadata row is removed first, then
     * the S3 object (see {@link AttachmentService#deleteAttachment} for
     * the orphan tradeoff). Only principals with access to the ticket
     * may delete its attachments.
     */
    @DeleteMapping("/attachments/{attachmentId}")
    @PreAuthorize("hasAnyRole('TECHNICIAN', 'DISPATCHER', 'REQUESTER')")
    public ResponseEntity<Void> delete(@PathVariable UUID attachmentId) {
        attachmentService.deleteAttachment(attachmentId, SecurityUtils.requireCurrentUser());
        return ResponseEntity.noContent().build();
    }
}
