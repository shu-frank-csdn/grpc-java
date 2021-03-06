/*
 * Copyright 2015, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *    * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *
 *    * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package io.grpc.netty;

import static com.google.common.base.Preconditions.checkNotNull;
import static io.grpc.netty.GrpcSslContexts.HTTP2_VERSION;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;

import io.grpc.Attributes;
import io.grpc.Grpc;
import io.grpc.Internal;
import io.grpc.Status;
import io.grpc.internal.GrpcUtil;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpClientUpgradeHandler;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http2.Http2ClientUpgradeCodec;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.OpenSslEngine;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.util.AsciiString;
import io.netty.util.ReferenceCountUtil;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Queue;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Nullable;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;

/**
 * Common {@link ProtocolNegotiator}s used by gRPC.
 */
@Internal
public final class ProtocolNegotiators {
  private static final Logger log = Logger.getLogger(ProtocolNegotiators.class.getName());

  private ProtocolNegotiators() {
  }

  /**
   * Create a server plaintext handler for gRPC.
   */
  public static ProtocolNegotiator serverPlaintext() {
    return new ProtocolNegotiator() {
      @Override
      public Handler newHandler(final GrpcHttp2ConnectionHandler handler) {
        class PlaintextHandler extends ChannelHandlerAdapter implements Handler {
          @Override
          public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
            // Just replace this handler with the gRPC handler.
            ctx.pipeline().replace(this, null, handler);
          }

          @Override
          public AsciiString scheme() {
            return Utils.HTTP;
          }
        }

        return new PlaintextHandler();
      }
    };
  }

  /**
   * Create a server TLS handler for HTTP/2 capable of using ALPN/NPN.
   */
  public static ProtocolNegotiator serverTls(final SslContext sslContext) {
    Preconditions.checkNotNull(sslContext, "sslContext");
    return new ProtocolNegotiator() {
      @Override
      public Handler newHandler(GrpcHttp2ConnectionHandler handler) {
        return new ServerTlsHandler(sslContext, handler);
      }
    };
  }

  @VisibleForTesting
  static final class ServerTlsHandler extends ChannelInboundHandlerAdapter
      implements ProtocolNegotiator.Handler {
    private final GrpcHttp2ConnectionHandler grpcHandler;
    private final SslContext sslContext;

    ServerTlsHandler(SslContext sslContext, GrpcHttp2ConnectionHandler grpcHandler) {
      this.sslContext = sslContext;
      this.grpcHandler = grpcHandler;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
      super.handlerAdded(ctx);

      SSLEngine sslEngine = sslContext.newEngine(ctx.alloc());
      ctx.pipeline().addFirst(new SslHandler(sslEngine, false));
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
      fail(ctx, cause);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
      if (evt instanceof SslHandshakeCompletionEvent) {
        SslHandshakeCompletionEvent handshakeEvent = (SslHandshakeCompletionEvent) evt;
        if (handshakeEvent.isSuccess()) {
          if (HTTP2_VERSION.equals(sslHandler(ctx.pipeline()).applicationProtocol())) {
            // Successfully negotiated the protocol.
            // Notify about completion and pass down SSLSession in attributes.
            grpcHandler.handleProtocolNegotiationCompleted(
                Attributes.newBuilder()
                    .set(Grpc.TRANSPORT_ATTR_SSL_SESSION,
                        sslHandler(ctx.pipeline()).engine().getSession())
                    .build());
            // Replace this handler with the GRPC handler.
            ctx.pipeline().replace(this, null, grpcHandler);
          } else {
            fail(ctx, new Exception(
                "Failed protocol negotiation: Unable to find compatible protocol."));
          }
        } else {
          fail(ctx, handshakeEvent.cause());
        }
      }
      super.userEventTriggered(ctx, evt);
    }

    private SslHandler sslHandler(ChannelPipeline pipeline) {
      return pipeline.get(SslHandler.class);
    }

    private void fail(ChannelHandlerContext ctx, Throwable exception) {
      logSslEngineDetails(Level.FINE, ctx, "TLS negotiation failed for new client.", exception);
      ctx.close();
    }

    @Override
    public AsciiString scheme() {
      return Utils.HTTPS;
    }
  }

  /**
   * Returns a {@link ProtocolNegotiator} that ensures the pipeline is set up so that TLS will
   * be negotiated, the {@code handler} is added and writes to the {@link io.netty.channel.Channel}
   * may happen immediately, even before the TLS Handshake is complete.
   */
  public static ProtocolNegotiator tls(SslContext sslContext, String authority) {
    Preconditions.checkNotNull(sslContext, "sslContext");
    URI uri = GrpcUtil.authorityToUri(Preconditions.checkNotNull(authority, "authority"));
    String host;
    int port;
    if (uri.getHost() != null) {
      host = uri.getHost();
      port = uri.getPort();
    } else {
      /*
       * Implementation note: We pick -1 as the port here rather than deriving it from the original
       * socket address.  The SSL engine doens't use this port number when contacting the remote
       * server, but rather it is used for other things like SSL Session caching.  When an invalid
       * authority is provided (like "bad_cert"), picking the original port and passing it in would
       * mean that the port might used under the assumption that it was correct.   By using -1 here,
       * it forces the SSL implementation to treat it as invalid.
       */
      host = authority;
      port = -1;
    }

    return new TlsNegotiator(sslContext, host, port);
  }

  static final class TlsNegotiator implements ProtocolNegotiator {
    private final SslContext sslContext;
    private final String host;
    private final int port;

    TlsNegotiator(SslContext sslContext, String host, int port) {
      this.sslContext = checkNotNull(sslContext, "sslContext");
      this.host = checkNotNull(host, "host");
      this.port = port;
    }

    @VisibleForTesting
    String getHost() {
      return host;
    }

    @VisibleForTesting
    int getPort() {
      return port;
    }

    @Override
    public Handler newHandler(GrpcHttp2ConnectionHandler handler) {
      ChannelHandler sslBootstrap = new ChannelHandlerAdapter() {
        @Override
        public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
          SSLEngine sslEngine = sslContext.newEngine(ctx.alloc(), host, port);
          SSLParameters sslParams = new SSLParameters();
          sslParams.setEndpointIdentificationAlgorithm("HTTPS");
          sslEngine.setSSLParameters(sslParams);
          ctx.pipeline().replace(this, null, new SslHandler(sslEngine, false));
        }
      };
      return new BufferUntilTlsNegotiatedHandler(sslBootstrap, handler);
    }
  }

  /**
   * Returns a {@link ProtocolNegotiator} used for upgrading to HTTP/2 from HTTP/1.x.
   */
  public static ProtocolNegotiator plaintextUpgrade() {
    return new PlaintextUpgradeNegotiator();
  }

  static final class PlaintextUpgradeNegotiator implements ProtocolNegotiator {
    @Override
    public Handler newHandler(GrpcHttp2ConnectionHandler handler) {
      // Register the plaintext upgrader
      Http2ClientUpgradeCodec upgradeCodec = new Http2ClientUpgradeCodec(handler);
      HttpClientCodec httpClientCodec = new HttpClientCodec();
      final HttpClientUpgradeHandler upgrader =
          new HttpClientUpgradeHandler(httpClientCodec, upgradeCodec, 1000);
      return new BufferingHttp2UpgradeHandler(upgrader);
    }
  }

  /**
   * Returns a {@link ChannelHandler} that ensures that the {@code handler} is added to the
   * pipeline writes to the {@link io.netty.channel.Channel} may happen immediately, even before it
   * is active.
   */
  public static ProtocolNegotiator plaintext() {
    return new PlaintextNegotiator();
  }

  static final class PlaintextNegotiator implements ProtocolNegotiator {
    @Override
    public Handler newHandler(GrpcHttp2ConnectionHandler handler) {
      return new BufferUntilChannelActiveHandler(handler);
    }
  }

  private static RuntimeException unavailableException(String msg) {
    return Status.UNAVAILABLE.withDescription(msg).asRuntimeException();
  }

  @VisibleForTesting
  static void logSslEngineDetails(Level level, ChannelHandlerContext ctx, String msg,
                                                @Nullable Throwable t) {
    if (!log.isLoggable(level)) {
      return;
    }

    SslHandler sslHandler = ctx.pipeline().get(SslHandler.class);
    SSLEngine engine = sslHandler.engine();

    StringBuilder builder = new StringBuilder(msg);
    builder.append("\nSSLEngine Details: [\n");
    if (engine instanceof OpenSslEngine) {
      builder.append("    OpenSSL, ");
      builder.append("Version: 0x").append(Integer.toHexString(OpenSsl.version()));
      builder.append(" (").append(OpenSsl.versionString()).append("), ");
      builder.append("ALPN supported: ").append(OpenSsl.isAlpnSupported());
    } else if (JettyTlsUtil.isJettyAlpnConfigured()) {
      builder.append("    Jetty ALPN");
    } else if (JettyTlsUtil.isJettyNpnConfigured()) {
      builder.append("    Jetty NPN");
    }
    builder.append("\n    TLS Protocol: ");
    builder.append(engine.getSession().getProtocol());
    builder.append("\n    Application Protocol: ");
    builder.append(sslHandler.applicationProtocol());
    builder.append("\n    Need Client Auth: " );
    builder.append(engine.getNeedClientAuth());
    builder.append("\n    Want Client Auth: ");
    builder.append(engine.getWantClientAuth());
    builder.append("\n    Supported protocols=");
    builder.append(Arrays.toString(engine.getSupportedProtocols()));
    builder.append("\n    Enabled protocols=");
    builder.append(Arrays.toString(engine.getEnabledProtocols()));
    builder.append("\n    Supported ciphers=");
    builder.append(Arrays.toString(engine.getSupportedCipherSuites()));
    builder.append("\n    Enabled ciphers=");
    builder.append(Arrays.toString(engine.getEnabledCipherSuites()));
    builder.append("\n]");

    log.log(level, builder.toString(), t);
  }

  /**
   * Buffers all writes until either {@link #writeBufferedAndRemove(ChannelHandlerContext)} or
   * {@link #fail(ChannelHandlerContext, Throwable)} is called. This handler allows us to
   * write to a {@link io.netty.channel.Channel} before we are allowed to write to it officially
   * i.e.  before it's active or the TLS Handshake is complete.
   */
  public abstract static class AbstractBufferingHandler extends ChannelDuplexHandler {

    private ChannelHandler[] handlers;
    private Queue<ChannelWrite> bufferedWrites = new ArrayDeque<ChannelWrite>();
    private boolean writing;
    private boolean flushRequested;
    private Throwable failCause;

    /**
     * @param handlers the ChannelHandlers are added to the pipeline on channelRegistered and
     *                 before this handler.
     */
    protected AbstractBufferingHandler(ChannelHandler... handlers) {
      this.handlers = handlers;
    }

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
      /**
       * This check is necessary as a channel may be registered with different event loops during it
       * lifetime and we only want to configure it once.
       */
      if (handlers != null) {
        ctx.pipeline().addFirst(handlers);
        handlers = null;
      }
      super.channelRegistered(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
      fail(ctx, cause);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
      fail(ctx, unavailableException("Connection broken while performing protocol negotiation"));
      super.channelInactive(ctx);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
        throws Exception {
      /**
       * This check handles a race condition between Channel.write (in the calling thread) and the
       * removal of this handler (in the event loop thread).
       * The problem occurs in e.g. this sequence:
       * 1) [caller thread] The write method identifies the context for this handler
       * 2) [event loop] This handler removes itself from the pipeline
       * 3) [caller thread] The write method delegates to the invoker to call the write method in
       *    the event loop thread. When this happens, we identify that this handler has been
       *    removed with "bufferedWrites == null".
       */
      if (failCause != null) {
        promise.setFailure(failCause);
        ReferenceCountUtil.release(msg);
      } else if (bufferedWrites == null) {
        super.write(ctx, msg, promise);
      } else {
        bufferedWrites.add(new ChannelWrite(msg, promise));
      }
    }

    @Override
    public void flush(ChannelHandlerContext ctx) {
      /**
       * Swallowing any flushes is not only an optimization but also required
       * for the SslHandler to work correctly. If the SslHandler receives multiple
       * flushes while the handshake is still ongoing, then the handshake "randomly"
       * times out. Not sure at this point why this is happening. Doing a single flush
       * seems to work but multiple flushes don't ...
       */
      if (bufferedWrites == null) {
        ctx.flush();
      } else {
        flushRequested = true;
      }
    }

    @Override
    public void close(ChannelHandlerContext ctx, ChannelPromise future) throws Exception {
      fail(ctx, unavailableException("Channel closed while performing protocol negotiation"));
    }

    protected final void fail(ChannelHandlerContext ctx, Throwable cause) {
      if (failCause == null) {
        failCause = cause;
      }
      if (bufferedWrites != null) {
        while (!bufferedWrites.isEmpty()) {
          ChannelWrite write = bufferedWrites.poll();
          write.promise.setFailure(cause);
          ReferenceCountUtil.release(write.msg);
        }
        bufferedWrites = null;
      }

      /**
       * In case something goes wrong ensure that the channel gets closed as the
       * NettyClientTransport relies on the channel's close future to get completed.
       */
      ctx.close();
    }

    protected final void writeBufferedAndRemove(ChannelHandlerContext ctx) {
      if (!ctx.channel().isActive() || writing) {
        return;
      }
      // Make sure that method can't be reentered, so that the ordering
      // in the queue can't be messed up.
      writing = true;
      while (!bufferedWrites.isEmpty()) {
        ChannelWrite write = bufferedWrites.poll();
        ctx.write(write.msg, write.promise);
      }
      assert bufferedWrites.isEmpty();
      bufferedWrites = null;
      if (flushRequested) {
        ctx.flush();
      }
      // Removal has to happen last as the above writes will likely trigger
      // new writes that have to be added to the end of queue in order to not
      // mess up the ordering.
      ctx.pipeline().remove(this);
    }

    private static class ChannelWrite {
      Object msg;
      ChannelPromise promise;

      ChannelWrite(Object msg, ChannelPromise promise) {
        this.msg = msg;
        this.promise = promise;
      }
    }
  }

  /**
   * Buffers all writes until the TLS Handshake is complete.
   */
  private static class BufferUntilTlsNegotiatedHandler extends AbstractBufferingHandler
      implements ProtocolNegotiator.Handler {

    private final GrpcHttp2ConnectionHandler grpcHandler;

    BufferUntilTlsNegotiatedHandler(
        ChannelHandler bootstrapHandler, GrpcHttp2ConnectionHandler grpcHandler) {
      super(bootstrapHandler, grpcHandler);
      this.grpcHandler = grpcHandler;
    }

    @Override
    public AsciiString scheme() {
      return Utils.HTTPS;
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
      if (evt instanceof SslHandshakeCompletionEvent) {
        SslHandshakeCompletionEvent handshakeEvent = (SslHandshakeCompletionEvent) evt;
        if (handshakeEvent.isSuccess()) {
          SslHandler handler = ctx.pipeline().get(SslHandler.class);
          if (HTTP2_VERSION.equals(handler.applicationProtocol())) {
            // Successfully negotiated the protocol.
            logSslEngineDetails(Level.FINER, ctx, "TLS negotiation succeeded.", null);

            // Successfully negotiated the protocol.
            // Notify about completion and pass down SSLSession in attributes.
            grpcHandler.handleProtocolNegotiationCompleted(
                Attributes.newBuilder()
                    .set(Grpc.TRANSPORT_ATTR_SSL_SESSION, handler.engine().getSession())
                    .build());
            writeBufferedAndRemove(ctx);
          } else {
            Exception ex = new Exception(
                "Failed ALPN negotiation: Unable to find compatible protocol.");
            logSslEngineDetails(Level.FINE, ctx, "TLS negotiation failed.", ex);
            fail(ctx, ex);
          }
        } else {
          fail(ctx, handshakeEvent.cause());
        }
      }
      super.userEventTriggered(ctx, evt);
    }
  }

  /**
   * Buffers all writes until the {@link io.netty.channel.Channel} is active.
   */
  private static class BufferUntilChannelActiveHandler extends AbstractBufferingHandler
      implements ProtocolNegotiator.Handler {

    BufferUntilChannelActiveHandler(ChannelHandler... handlers) {
      super(handlers);
    }

    @Override
    public AsciiString scheme() {
      return Utils.HTTP;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
      writeBufferedAndRemove(ctx);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
      writeBufferedAndRemove(ctx);
      super.channelActive(ctx);
    }
  }

  /**
   * Buffers all writes until the HTTP to HTTP/2 upgrade is complete.
   */
  private static class BufferingHttp2UpgradeHandler extends AbstractBufferingHandler
      implements ProtocolNegotiator.Handler {

    BufferingHttp2UpgradeHandler(ChannelHandler... handlers) {
      super(handlers);
    }

    @Override
    public AsciiString scheme() {
      return Utils.HTTP;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
      // Trigger the HTTP/1.1 plaintext upgrade protocol by issuing an HTTP request
      // which causes the upgrade headers to be added
      DefaultHttpRequest upgradeTrigger =
          new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
      ctx.writeAndFlush(upgradeTrigger);
      super.channelActive(ctx);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
      if (evt == HttpClientUpgradeHandler.UpgradeEvent.UPGRADE_SUCCESSFUL) {
        writeBufferedAndRemove(ctx);
      } else if (evt == HttpClientUpgradeHandler.UpgradeEvent.UPGRADE_REJECTED) {
        fail(ctx, unavailableException("HTTP/2 upgrade rejected"));
      }
      super.userEventTriggered(ctx, evt);
    }
  }
}
