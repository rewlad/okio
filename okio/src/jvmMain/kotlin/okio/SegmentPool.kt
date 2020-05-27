/*
 * Copyright (C) 2014 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okio

import okio.SegmentPool.MAX_SIZE
import okio.SegmentPool.recycle
import okio.SegmentPool.take
import java.lang.Integer.parseInt
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * This class pools segments in a lock-free singly-linked stack. Though this code is lock-free it
 * does use a sentinel [LOCK] value to defend against races.
 *
 * When popping, a caller swaps the stack's next pointer with the [LOCK] sentinel. If the stack was
 * not already locked, the caller replaces the head node with its successor.
 *
 * When pushing, a caller swaps the head with a new node whose successor is the replaced head.
 *
 * If operations conflict, segments are not pushed into the stack. A [recycle] call that loses a
 * race will not add to the pool, and a [take] call that loses a race will not take from the pool.
 * Under significant contention this pool will have fewer hits and the VM will do more GC and zero
 * filling of arrays.
 *
 * Note that the [MAX_SIZE] may be exceeded if multiple calls to [recycle] race. Exceeding the
 * target pool size by a few segments doesn't harm performance, and imperfect enforcement is less
 * code.
 */

internal interface AbstractSegmentPool {
  fun take(): Segment
  fun recycle(segment: Segment)
  fun getByteCount(): Long
}

internal actual object SegmentPool {
  private val impl: AbstractSegmentPool = when(System.getenv("OKIO_SEGMENT_POOL")) {
    "NOOP" -> NoOpSegmentPool()
    "THREAD_LOCAL" -> ThreadLocalSegmentPool()
    "CAS" -> CASSegmentPool()
    else -> throw Exception("need env OKIO_SEGMENT_POOL=NOOP|CAS|THREAD_LOCAL")
  }

  /** The maximum number of bytes to pool.  */
  // TODO: Is 64 KiB a good maximum size? Do we ever have that many idle segments?
  actual val MAX_SIZE = parseInt(System.getenv("OKIO_SEGMENT_POOL_SIZE")?:"64") * 1024L // default is 64 KiB.
  @JvmStatic actual fun take(): Segment = impl.take()
  @JvmStatic actual fun recycle(segment: Segment) = impl.recycle(segment)
  actual val byteCount: Long = impl.getByteCount()
}

internal class NoOpSegmentPool: AbstractSegmentPool {
  override fun take(): Segment = Segment()
  override fun recycle(segment: Segment) {}
  override fun getByteCount(): Long = 0L
}


internal class UnsafeSegmentPool(): AbstractSegmentPool {
  private var first: Segment? = null
  private var byteCount = 0L
  override fun getByteCount(): Long = byteCount
  override fun take(): Segment {
    val res = first
    return if(res === null) Segment() else {
      first = res.next
      res.next = null
      byteCount -= Segment.SIZE
      res
    }
  }
  override fun recycle(segment: Segment) {
    require(segment.next == null && segment.prev == null)
    if (segment.shared) return // This segment cannot be recycled.
    if (byteCount >= MAX_SIZE) return // Pool is full.
    segment.next = first
    segment.limit = 0
    segment.pos = 0
    first = segment
    byteCount += Segment.SIZE
  }
}

internal class ThreadLocalSegmentPool: AbstractSegmentPool {
  private val impl = object: ThreadLocal<AbstractSegmentPool>() {
    override fun initialValue(): AbstractSegmentPool = UnsafeSegmentPool()
  }
  override fun take(): Segment = impl.get().take()
  override fun recycle(segment: Segment) = impl.get().recycle(segment)
  override fun getByteCount(): Long = impl.get().getByteCount()
}

internal class CASSegmentPool: AbstractSegmentPool {

  /** A sentinel segment to indicate that the linked list is currently being modified. */
  private val LOCK = Segment(ByteArray(0), pos = 0, limit = 0, shared = false, owner = false)

  /** Singly-linked list of segments. */
  private val firstRef = AtomicReference<Segment?>()

  /** Total bytes in this pool. */
  private val atomicByteCount = AtomicLong()

  override fun getByteCount(): Long = atomicByteCount.get()

  override fun take(): Segment {
    val first = firstRef.getAndSet(LOCK)
    when {
      first === LOCK -> {
        // We didn't acquire the lock. Don't take a pooled segment.
        return Segment()
      }
      first == null -> {
        // We acquired the lock but the pool was empty. Unlock and return a new segment.
        firstRef.set(null)
        return Segment()
      }
      else -> {
        // We acquired the lock and the pool was not empty. Pop the first element and return it.
        firstRef.set(first.next)
        first.next = null
        atomicByteCount.addAndGet(-Segment.SIZE.toLong())
        return first
      }
    }
  }

  override fun recycle(segment: Segment) {
    require(segment.next == null && segment.prev == null)
    if (segment.shared) return // This segment cannot be recycled.
    if (atomicByteCount.get() >= MAX_SIZE) return // Pool is full.

    val first = firstRef.get()
    if (first === LOCK) return // A take() is currently in progress.

    segment.next = first
    segment.limit = 0
    segment.pos = 0

    if (firstRef.compareAndSet(first, segment)) {
      // We successfully recycled this segment. Adjust the pool size.
      atomicByteCount.addAndGet(Segment.SIZE.toLong())
    } else {
      // We raced another operation. Don't recycle this segment.
      segment.next = null
    }
  }
}
