package com.fieldwork.ops.attachment;

import com.fieldwork.ops.auth.User;
import com.fieldwork.ops.auth.UserRepository;
import com.fieldwork.ops.common.exception.InvalidAttachmentException;
import com.fieldwork.ops.common.exception.ResourceNotFoundException;
import com.fieldwork.ops.common.security.CurrentUser;
import com.fieldwork.ops.workorder.Attachment;
import com.fieldwork.ops.workorder.AttachmentRepository;
import com.fieldwork.ops.workorder.WorkOrder;
import com.fieldwork.ops.workorder.WorkOrderService;
import com.fieldwork.ops.workorder.dto.AttachmentResponse;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Ticket attachments: S3 storage plus the {@link Attachment} metadata
 * row. Ticket-level access control is reused from
 * {@link WorkOrderService#checkTicketAccess} — the service layer stays
 * the last line of defense behind the controller's role gates.
 *
 * <p>Failure ordering (the no-orphan guarantee):
 * <ul>
 *   <li><b>Upload</b> writes the S3 object <em>first</em>, then the DB
 *       row. If the row write fails, the object is deleted again
 *       (compensating action); the compensation is best-effort — its
 *       own failure is logged but never masks the original error.</li>
 *   <li><b>Delete</b> removes the DB row first, then the S3 object (see
 *       {@link #deleteAttachment} for the tradeoff).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AttachmentService {

    /** Upper bound on the sanitized file name embedded in the S3 key. */
    private static final int MAX_FILE_NAME_LENGTH = 120;

    private final S3Client s3;
    private final S3Properties s3Properties;
    private final AttachmentRepository attachments;
    private final UserRepository users;
    private final WorkOrderService workOrderService;

    /**
     * Stores an uploaded file on the ticket.
     *
     * <p>Validates the file (non-empty, allowlisted content type,
     * within the size limit), then puts the object to S3 and persists
     * the metadata row. The actor is always the authenticated principal
     * — the request carries no uploader id.
     *
     * @return the stored attachment metadata
     * @throws InvalidAttachmentException when the file fails validation
     */
    @Transactional
    public AttachmentResponse upload(UUID workOrderId, MultipartFile file, CurrentUser actor) {
        validate(file);
        // Loads the ticket AND enforces the ownership rule (technician →
        // assigned tickets only, requester → own tickets only). The
        // returned entity is detached after this call, which is fine:
        // only its id (FK) and ticket number (basic attribute) are used.
        WorkOrder workOrder = workOrderService.getById(workOrderId, actor);
        User uploader = users.findById(actor.id())
                .orElseThrow(() -> new ResourceNotFoundException("User", actor.id()));

        String fileName = sanitizeFileName(file.getOriginalFilename());
        String key = "work-orders/%s/%s-%s"
                .formatted(workOrder.getTicketNumber(), UUID.randomUUID(), fileName);
        byte[] bytes = readBytes(file);
        String checksum = sha256Hex(bytes);

        // Object storage FIRST: the metadata row must never reference a
        // missing object. If the persist below fails, this object is
        // deleted again (compensating action) so no orphan remains in S3.
        s3.putObject(
                PutObjectRequest.builder()
                        .bucket(s3Properties.bucket())
                        .key(key)
                        .contentType(file.getContentType())
                        .contentLength((long) bytes.length)
                        .build(),
                RequestBody.fromBytes(bytes));

        Attachment attachment = new Attachment();
        attachment.setWorkOrder(workOrder);
        attachment.setUploadedBy(uploader);
        attachment.setFileName(fileName);
        attachment.setContentType(file.getContentType());
        attachment.setSizeBytes(bytes.length);
        attachment.setStorageKey(key);
        attachment.setChecksumSha256(checksum);
        attachment.setCreatedBy(actor.email());
        attachment.setUpdatedBy(actor.email());
        try {
            // Flush so @CreationTimestamp is populated before the DTO is mapped.
            attachments.saveAndFlush(attachment);
        } catch (RuntimeException dbFailure) {
            compensateDelete(key);
            throw dbFailure;
        }
        log.info(
                "Stored attachment {} ({} bytes, sha256 {}) for ticket {} as S3 key {}",
                fileName, bytes.length, checksum, workOrder.getTicketNumber(), key);
        return toResponse(attachment);
    }

    /**
     * Fetches an attachment's bytes for download. The caller must have
     * access to the ticket the attachment belongs to.
     */
    @Transactional(readOnly = true)
    public AttachmentDownload download(UUID attachmentId, CurrentUser actor) {
        Attachment attachment = load(attachmentId);
        workOrderService.checkTicketAccess(attachment.getWorkOrder(), actor);
        byte[] bytes = s3.getObjectAsBytes(GetObjectRequest.builder()
                        .bucket(s3Properties.bucket())
                        .key(attachment.getStorageKey())
                        .build())
                .asByteArray();
        return new AttachmentDownload(
                attachment.getId(),
                attachment.getFileName(),
                attachment.getContentType(),
                bytes);
    }

    /**
     * Lists a ticket's attachments as metadata DTOs (mapped inside the
     * transaction so the lazy {@code uploadedBy} association is
     * available). Used for the ticket-detail response.
     */
    @Transactional(readOnly = true)
    public List<AttachmentResponse> listForTicket(UUID workOrderId, CurrentUser actor) {
        // 404 on unknown tickets, and the same visibility rule as the
        // ticket detail itself.
        workOrderService.getById(workOrderId, actor);
        return attachments.findByWorkOrderId(workOrderId).stream()
                .map(AttachmentService::toResponse)
                .toList();
    }

    /**
     * Deletes an attachment.
     *
     * <p><b>Orphan tradeoff:</b> the DB row is deleted <em>first</em>,
     * then the S3 object. If the S3 delete fails, the row is already
     * gone — the attachment is invisible to users, so no one can hit a
     * broken download; the leftover object is pure storage waste, to be
     * reaped by a later GC pass. The reverse order (S3 first) would risk
     * the worse failure: a surviving DB row pointing at a missing
     * object, i.e. a visibly broken download. The S3 failure is
     * therefore caught and logged, never rethrown — rethrowing would
     * roll the DB delete back and produce exactly the dangling row we
     * are avoiding.
     */
    @Transactional
    public void deleteAttachment(UUID attachmentId, CurrentUser actor) {
        Attachment attachment = load(attachmentId);
        workOrderService.checkTicketAccess(attachment.getWorkOrder(), actor);
        String key = attachment.getStorageKey();
        attachments.delete(attachment);
        try {
            s3.deleteObject(DeleteObjectRequest.builder()
                    .bucket(s3Properties.bucket())
                    .key(key)
                    .build());
        } catch (RuntimeException s3Failure) {
            log.warn(
                    "Attachment row {} deleted but S3 object {} could not be removed; "
                            + "orphaned object left for a later GC pass",
                    attachmentId, key, s3Failure);
        }
        log.info("Deleted attachment {} (S3 key {})", attachmentId, key);
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private Attachment load(UUID attachmentId) {
        return attachments
                .findById(attachmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Attachment", attachmentId));
    }

    /** Best-effort removal of an object whose DB row was never written. */
    private void compensateDelete(String key) {
        try {
            s3.deleteObject(DeleteObjectRequest.builder()
                    .bucket(s3Properties.bucket())
                    .key(key)
                    .build());
            log.info("Compensating delete removed orphaned S3 object {}", key);
        } catch (RuntimeException compensationFailure) {
            // Never mask the original DB failure: the orphan is logged
            // for a later GC pass instead.
            log.warn(
                    "Compensating delete of S3 object {} failed; orphaned object left for a later GC pass",
                    key, compensationFailure);
        }
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidAttachmentException("Attachment must not be empty");
        }
        String contentType = file.getContentType();
        if (contentType == null || !s3Properties.allowedContentTypes().contains(contentType)) {
            throw new InvalidAttachmentException(
                    "Unsupported content type '%s'. Allowed types: %s"
                            .formatted(contentType, String.join(", ", s3Properties.allowedContentTypes())));
        }
        if (file.getSize() > s3Properties.maxFileSizeBytes()) {
            throw new InvalidAttachmentException(
                    "Attachment is %d bytes; the maximum is %d bytes"
                            .formatted(file.getSize(), s3Properties.maxFileSizeBytes()));
        }
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (java.io.IOException e) {
            throw new UncheckedIOException("Failed to read uploaded file", e);
        }
    }

    /**
     * Makes a client-supplied file name safe to embed in an S3 key:
     * strips any path segments, keeps only letters, digits, dot, dash
     * and underscore, and drops leading dots (no dotfiles, no
     * {@code ..}).
     */
    private static String sanitizeFileName(String original) {
        String name = original == null ? "" : original.strip().replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        StringBuilder cleaned = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            cleaned.append(
                    Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_' ? c : '_');
        }
        String result = cleaned.toString().replaceAll("^\\.+", "");
        if (result.isBlank()) {
            result = "file";
        }
        return result.length() > MAX_FILE_NAME_LENGTH
                ? result.substring(0, MAX_FILE_NAME_LENGTH)
                : result;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder hex = new StringBuilder(2 * hash.length);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 message digest is not available", e);
        }
    }

    private static AttachmentResponse toResponse(Attachment attachment) {
        User uploader = attachment.getUploadedBy();
        return new AttachmentResponse(
                attachment.getId(),
                attachment.getFileName(),
                attachment.getContentType(),
                attachment.getSizeBytes(),
                uploader == null
                        ? null
                        : new AttachmentResponse.Uploader(
                                uploader.getId(), uploader.getUsername(), uploader.getFullName()),
                attachment.getCreatedAt());
    }
}
