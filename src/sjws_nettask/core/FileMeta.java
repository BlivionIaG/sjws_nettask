/*
 * Copyright (C) 2026 BlivionIaG <BlivionIaG at chenco.tk>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package sjws_nettask.core;

import java.time.Instant;

/**
 * Validators and representation fields of one file version, computed once (and kept by the
 * {@link FileCache} with the bytes).
 *
 * @param validator identity of the version
 * @param lastModified mtime truncated to seconds (what Last-Modified says and If-Modified-Since compares)
 * @param etag entity tag (see {@link EntityTag})
 * @param contentType Content-Type value
 */
public record FileMeta(Validator validator, Instant lastModified, String lastModifiedText, String etag,
        String contentType) {

    public static FileMeta of(Validator v, String name, EntityTag.Policy policy) {
        var modified = Instant.ofEpochSecond(Math.floorDiv(v.mtimeNanos(), 1_000_000_000L));
        return new FileMeta(v, modified, HttpDate.format(modified), policy.tag(v), Mime.contentType(Mime.of(name)));
    }

    /** Fields of a 200 (and HEAD) response. */
    public ResponseHeaders headers200() {
        return ResponseHeaders.of()
                .add("last-modified", this.lastModifiedText)
                .add("etag", this.etag)
                .add("accept-ranges", StaticFileHandler.ACCEPT_RANGES)
                .add("content-type", this.contentType)
                .add("x-content-type-options", StaticFileHandler.NOSNIFF);
    }

    /**
     * Fields of a 304: its own set (RFC 9110 §15.4.5: ETag, plus Last-Modified to guide cache
     * updates; Date is added by the writer; Cache-Control once the server sends one), not the 200's.
     */
    public ResponseHeaders headers304() {
        return ResponseHeaders.of()
                .add("etag", this.etag)
                .add("last-modified", this.lastModifiedText);
    }

    /**
     * RFC 9110 §13.2.2 for GET/HEAD: If-None-Match decides when present (and If-Modified-Since is
     * then ignored); otherwise a valid If-Modified-Since not older than Last-Modified.
     *
     * @return true if the answer is 304
     */
    public boolean notModified(RequestHeaders conditionals) {
        if (conditionals.ifNoneMatch() != null) {
            return EntityTag.noneMatchHits(conditionals.ifNoneMatch(), this.etag);
        }
        var since = HttpDate.parse(conditionals.ifModifiedSince());
        return since != null && !this.lastModified.isAfter(since);
    }
}
