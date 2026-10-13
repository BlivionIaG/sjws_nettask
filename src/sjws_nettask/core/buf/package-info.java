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
/**
 * Pooled direct {@link java.nio.ByteBuffer}s shared by the HTTP transports and messaging
 * (design: {@code docs/design/buffer-pool.md}).
 *
 * <h2>Using it</h2>
 * <pre>{@code
 * BufferBudget msg = BufferPool.shared().budget(BufferPool.MESSAGING);
 * try (PooledBuffer b = msg.acquire(4096)) {     // never null
 *     ByteBuffer bb = b.buffer();                 // position 0, limit = capacity (>= 4096)
 *     ...
 * }                                               // close() == release()
 * }</pre>
 *
 * <h2>Ownership rules</h2>
 * <ol>
 * <li>{@code acquire}, {@code tryAcquire} and {@code acquireLarge} return a buffer with one reference, owned by
 *     the caller.</li>
 * <li>Handing a buffer to another thread (a queue, a future) <b>transfers</b> ownership. The sender must not touch
 *     it, or any view of it, afterwards. The receiver releases it. Release may happen on any thread.</li>
 * <li>Release exactly once per reference: {@link PooledBuffer#release()}, or try-with-resources. Releasing more
 *     often than acquired plus retained throws {@link IllegalStateException}.</li>
 * <li>{@link PooledBuffer#retain()} adds a reference (e.g. one segment chunk read by several consumers). Each
 *     {@code retain} needs its own {@code release}.</li>
 * <li>Views ({@code duplicate()}, {@code slice()}) of {@link PooledBuffer#buffer()} must not outlive the last
 *     release. Data kept longer (response bodies, decoded records) must be copied out, or the holder must
 *     {@code retain()}.</li>
 * <li>Do not keep a pooled buffer as long-lived per-object state (per partition, per connection while idle,
 *     unless that is the measured design). In particular {@link BufferBudget#acquireLarge} buffers are for
 *     transient work (recovery, compaction, a scan) and are released when it ends.</li>
 * </ol>
 *
 * <h2>Budgets</h2>
 * One global cap ({@code sjws.buf.maxBytes}) and named per-tenant budgets ({@link BufferPool#HTTP},
 * {@link BufferPool#MESSAGING}; messaging defaults to 50 %). A buffer is charged to the budget it came from until its
 * last release. {@link BufferBudget#acquire} never fails: over budget it returns an unpooled heap buffer
 * (counted as a fallback). {@link BufferBudget#tryAcquire} returns {@code null} instead, so a caller can shed load
 * (messaging fetch answers 503 + Retry-After).
 *
 * <h2>Off by default</h2>
 * Unless {@code -Dsjws.buf.pool=on}, {@link BufferPool#shared()} is {@link BufferPool#disabled()}: every acquire is a
 * plain heap {@code ByteBuffer.allocate} with no pooling, no Cleaner and no accounting. Callers must not assume
 * {@link java.nio.ByteBuffer#isDirect()}.
 *
 * <h2>Leak detection</h2>
 * A sampled fraction of acquires ({@code sjws.buf.leakSample}, 1 in N; see the design doc for the measured cost)
 * is tracked by a {@link java.lang.ref.Cleaner}: an unreleased buffer that becomes unreachable is returned to the
 * pool, counted in {@link BufferPool.Stats#leaked()} and logged (rate-limited). With {@code -Dsjws.buf.debug=true}
 * every acquire is tracked with its stack, released memory is poisoned (0xDE), use after release throws, and
 * {@link BufferPool#outstanding()} lists what is still held. Tests use the {@code NoBufferLeaks} JUnit extension.
 */
package sjws_nettask.core.buf;
