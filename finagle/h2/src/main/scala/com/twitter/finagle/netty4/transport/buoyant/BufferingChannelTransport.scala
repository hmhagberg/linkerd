package com.twitter.finagle.netty4.transport.buoyant

import java.net.InetSocketAddress

import com.twitter.concurrent.AsyncQueue
import com.twitter.finagle.ChannelException
import com.twitter.finagle.netty4.transport.ChannelTransport
import com.twitter.util.{Future, Promise}
import io.netty.channel.{ChannelFuture, EventLoop}
import io.netty.{channel => nettyChan}
import java.util
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

import com.twitter.logging.Logger
import io.netty.handler.codec.http2.Http2PingFrame

/**
 * A Transport implementation based on Netty's Channel which buffers writes.  This Transport buffers
 * writes to a queue and schedules an event on the channel's event loop to write the items and then
 * flush them.  This allows writes to be batched and flushed together rather than needing to flush
 * after each individual write.  The result is fewer syscalls which leads to better performance.
 * This approach was highly inspired by grpc-java's WriteQueue:
 * https://github.com/grpc/grpc-java/pull/431
 *
 * @param ch the underlying netty channel
 *
 * @param readQueue the queue used to buffer inbound messages
 *
 * @note During the construction, a `ChannelTransport` inserts the terminating
 *       inbound channel handler into the channel's pipeline so any inbound channel
 *       handlers inserted after that won't get any of the inbound traffic.
 */
class BufferingChannelTransport(
  ch: nettyChan.Channel,
  readQueue: AsyncQueue[Any] = new AsyncQueue[Any],
  omitStackTraceOnInactive: Boolean = false
) extends ChannelTransport(ch, readQueue, omitStackTraceOnInactive) {

  private val log = Logger.get("h2")

  // Satisfy the done promise when the write completes.
  private case class WriteItem(msg: Any, done: Promise[Unit])

  // Always flush after this many messages.
  // This value was cargo-culted from
  // https://github.com/grpc/grpc-java/pull/431/files#diff-7f048858dab93d58f2bcac583626abddR49
  // This is mostly just a safety net to ensure that we don't buffer up arbitrarily large writes
  // without flushing. In most cases I would expect us to flush before hitting this limit.
  private[this] val MaxFlushSize = 128
  private[this] val flushScheduled = new AtomicBoolean(false)
  private[this] val flushCounter = new AtomicInteger(0)
  private[this] val writeQueue = new LinkedBlockingQueue[WriteItem]()
  private[this] val writeChunk = new util.ArrayDeque[WriteItem](MaxFlushSize)

  override def write(msg: Any): Future[Unit] = {
    val p = new Promise[Unit]()
    writeQueue.add(WriteItem(msg, p))
    scheduleFlush()
    p
  }

  private[this] def scheduleFlush(): Unit = {
    if (flushScheduled.compareAndSet(false, true)) {
      val index = flushCounter.incrementAndGet()
      log.debug(s"Scheduling write queue flush #$index, size ${writeQueue.size()}, event loop ${ch.eventLoop()}")
      ch.eventLoop().execute(flush(index, ch.eventLoop()))
    }
  }

  private[this] def flush(index: Int, eventLoop: EventLoop): Runnable = { () =>
    var flushed = false
    var flushedItems = 0
    log.debug(s"Flushing write queue #$index, size ${writeQueue.size()}, event loop $eventLoop")
    if (writeQueue.drainTo(writeChunk, MaxFlushSize) > 0) {
      while (writeChunk.size > 0) {
        val item = writeChunk.poll()
        //if (ch.remoteAddress().asInstanceOf[InetSocketAddress].getPort == 5144) {
        //  log.debug(s"Writing ${item.msg}")
        //}
        if (item.msg.isInstanceOf[Http2PingFrame]) {
          log.debug(s"Writing ${item.msg}, ch $ch")
        }
        val f = toFuture(ch.write(item.msg))
        flushedItems += 1
        if (item.msg.isInstanceOf[Http2PingFrame]) {
          log.debug(s"Wrote ${item.msg}, ch $ch")
        }
        item.done.become(f)
      }
      flushed = true
      ch.flush()
    }
    // Always flush at least once.
    if (!flushed) {
      ch.flush()
    }

    log.debug(s"Flushed write queue #$index, items $flushedItems, event loop $eventLoop")
    flushScheduled.set(false)
    if (!writeQueue.isEmpty) {
      scheduleFlush()
    }
  }

  private[this] def toFuture(op: ChannelFuture): Future[Unit] = {
    val p = new Promise[Unit]
    op.addListener { f: ChannelFuture =>
      if (f.isSuccess) {
        p.setDone(); ()
      } else {
        p.setException(ChannelException(f.cause, context.remoteAddress))
      }
    }
    p
  }
}

