/**
 * File attachments: S3 upload/download and metadata persistence.
 * Guarantees no orphan S3 objects — if the database write fails after a
 * successful upload, the object is deleted (compensating action).
 */
package com.fieldwork.ops.attachment;
