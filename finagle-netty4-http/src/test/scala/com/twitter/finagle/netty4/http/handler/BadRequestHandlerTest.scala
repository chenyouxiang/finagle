package com.twitter.finagle.netty4.http.handler

import com.twitter.finagle.http.Status
import io.netty.buffer.ByteBuf
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.DecoderResult
import io.netty.handler.codec.http._
import org.scalatest.FunSuite

class BadRequestHandlerTest extends FunSuite {

  // Get an embedded channel with the BadRequestHandler installed.
  private def getChannel: EmbeddedChannel = new EmbeddedChannel(BadRequestHandler)

  // Send data through the netty codec to ensure we get the same errors that
  // are generated by the codec itself
  def genInvalidRequest(
    maxRequestLineLen: Int,
    maxHeaderSize: Int
  )(req: HttpRequest
  ): HttpRequest = {
    val encodeChannel = new EmbeddedChannel(new HttpRequestEncoder())
    val decodeChannel = new EmbeddedChannel(
      new HttpRequestDecoder(maxRequestLineLen, maxHeaderSize, Int.MaxValue)
    )

    assert(encodeChannel.writeOutbound(req))
    while (!encodeChannel.outboundMessages().isEmpty &&
      decodeChannel.writeInbound(encodeChannel.readOutbound[ByteBuf]())) {}

    val result = decodeChannel.readInbound[HttpRequest]()
    assert(result.decoderResult.isFailure)
    result
  }

  test("Passes through valid requests") {
    val ch = getChannel
    val content = ch.alloc().directBuffer()
    content.writeBytes(Array[Byte](1, 2, 3, 4))
    val req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/foo", content)

    try {
      assert(req.decoderResult().isSuccess)
      assert(ch.writeInbound(req))
      assert(ch.readInbound[HttpRequest]() eq req)

      // make sure we didn't free the content for valid requests
      assert(req.content() eq content)
      assert(req.content().refCnt() > 0)
    } finally {
      if (content.refCnt() > 0) content.release()
    }
  }

  test("Invalid messages have any existing buffer released") {
    val ch = getChannel
    val content = ch.alloc().directBuffer()
    content.writeBytes(Array[Byte](1, 2, 3, 4))
    val req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/foo", content)
    req.setDecoderResult(DecoderResult.failure(new Exception("boom")))

    try {
      assert(req.decoderResult().isFailure)
      assert(!ch.writeInbound(req)) // we shouldn't get a message in the inbound queue.
      assert(ch.readInbound[HttpRequest]() eq null)

      // make sure we did free the content for invalid requests
      assert(req.content() eq content)
      assert(req.content().refCnt() == 0)
    } finally {
      if (content.refCnt() > 0) content.release()
    }
  }

  private val tooLongUri = genInvalidRequest(10, Int.MaxValue) {
    new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/wayToLong" * 1024)
  }

  private val tooLargeHeaders = genInvalidRequest(Int.MaxValue, 10) {
    val req = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/foo")
    req.headers().add("HeaderName", "Way To Long" * 1024)
    req
  }

  private val randomError = {
    val req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/foo")
    req.setDecoderResult(DecoderResult.failure(new Exception("boom")))
    req
  }

  Seq(
    Status.RequestURITooLong -> tooLongUri,
    Status.RequestHeaderFieldsTooLarge -> tooLargeHeaders,
    Status.BadRequest -> randomError
  ).foreach {
    case (status, badReq) =>
      test(s"Too long request line is converted into a $status response") {
        val ch = getChannel
        assert(!ch.writeInbound(badReq)) // message should not make it through
        assert(ch.inboundMessages().isEmpty)
        val out = ch.readOutbound[HttpResponse]()
        assert(out.status.code == status.code)
      }
  }

  test("Hangs up if there is a decode error in an HTTP chunk (eg invalid trailers)") {
    val ch = getChannel
    val content = ch.alloc.directBuffer()
    content.writeBytes(Array[Byte](1, 2, 3, 4))

    val req = new DefaultLastHttpContent(content)
    req.setDecoderResult(DecoderResult.failure(new Exception("boom")))

    assert(req.decoderResult.isFailure)
    assert(!ch.writeInbound(req))
    assert(ch.readInbound[Any] == null) // we shouldn't get a message in the inbound queue.
    assert(ch.readOutbound[Any] == null) // we shouldn't get a message in the outbound queue.
    assert(!ch.isActive)
    // make sure we did free the content for invalid requests
    assert(req.content eq content)
    assert(req.content.refCnt == 0)
  }
}
