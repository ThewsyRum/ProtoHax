package dev.sora.relay

import com.nukkitx.natives.sha256.Sha256
import com.nukkitx.natives.util.Natives
import com.nukkitx.network.raknet.*
import com.nukkitx.network.util.DisconnectReason
import com.nukkitx.protocol.bedrock.BedrockPacket
import com.nukkitx.protocol.bedrock.BedrockPacketCodec
import com.nukkitx.protocol.bedrock.DummyBedrockSession
import com.nukkitx.protocol.bedrock.annotation.Incompressible
import com.nukkitx.protocol.bedrock.wrapper.BedrockWrapperSerializerV11
import com.nukkitx.protocol.bedrock.wrapper.BedrockWrapperSerializerV9_10
import com.nukkitx.protocol.bedrock.wrapper.compression.CompressionSerializer
import com.nukkitx.protocol.bedrock.wrapper.compression.NoCompression
import dev.sora.relay.utils.CipherPair
import dev.sora.relay.utils.logInfo
import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufAllocator
import io.netty.buffer.Unpooled
import io.netty.channel.EventLoop
import io.netty.util.internal.logging.InternalLoggerFactory
import java.nio.ByteBuffer
import java.util.*
import java.util.zip.Deflater


class RakNetRelaySession(val clientsideSession: RakNetServerSession,
                         val serversideSession: RakNetClientSession,
                         private val eventLoop: EventLoop, private val packetCodec: BedrockPacketCodec,
                         val listener: RakNetRelaySessionListener) {

    private var clientState = RakNetState.INITIALIZING
    private var serverState = RakNetState.INITIALIZING

    val clientSerializer = listener.provideSerializer(clientsideSession)
    val serverSerializer = listener.provideSerializer(serversideSession)
    private val bedrockSession = DummyBedrockSession(eventLoop)

    var clientCipher: CipherPair? = null
    var serverCipher: CipherPair? = null

    private val pendingPackets = mutableListOf<ByteBuf>()

    private val log = InternalLoggerFactory.getInstance(RakNetRelaySession::class.java)

    init {
        listener.session = this
        serversideSession.listener = RakNetRelayServerListener()
        clientsideSession.listener = RakNetRelayClientListener()
    }

    fun injectInbound(packet: ByteArray) {
        injectInbound(Unpooled.copiedBuffer(packet))
    }

    fun injectInbound(packet: ByteBuf) {
        clientsideSession.send(packet)
    }

    fun injectOutbound(packet: ByteArray) {
        injectOutbound(Unpooled.copiedBuffer(packet))
    }

    fun injectOutbound(packet: ByteBuf) {
        serversideSession.send(packet)
    }

    fun inboundPacket(packet: BedrockPacket) {
        sendWrapped(packet, true)
    }

    fun outboundPacket(packet: BedrockPacket) {
        sendWrapped(packet, false)
    }

    private fun generateTrailer(buf: ByteBuf, cipherPair: CipherPair): ByteArray? {
        val hash: Sha256 = Natives.SHA_256.get()
        val counterBuf = ByteBufAllocator.DEFAULT.directBuffer(8)
        return try {
            counterBuf.writeLongLE(cipherPair.sentEncryptedPacketCount.getAndIncrement())
            val keyBuffer = ByteBuffer.wrap(cipherPair.secretKey.encoded)
            hash.update(counterBuf.internalNioBuffer(0, 8))
            hash.update(buf.internalNioBuffer(buf.readerIndex(), buf.readableBytes()))
            hash.update(keyBuffer)
            val digested = hash.digest()
            Arrays.copyOf(digested, 8)
        } finally {
            counterBuf.release()
            hash.reset()
        }
    }

    private fun sendWrapped(packet: BedrockPacket, isClientside: Boolean) {
        val serializer = if (isClientside) clientSerializer else serverSerializer

        val compressed = ByteBufAllocator.DEFAULT.ioBuffer()
        var compression: CompressionSerializer? = null
        if (packet.javaClass.isAnnotationPresent(Incompressible::class.java) && serializer is BedrockWrapperSerializerV11) {
            compression = serializer.compressionSerializer
            serializer.compressionSerializer = NoCompression.INSTANCE
        }
        try {
            serializer.serialize(compressed, packetCodec, listOf(packet), Deflater.DEFAULT_COMPRESSION, bedrockSession)
            sendSerialized(compressed, isClientside)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            compressed?.release()
            if (compression != null) {
                (serializer as BedrockWrapperSerializerV11).compressionSerializer = compression
            }
        }
    }

    private fun sendSerialized(compressed: ByteBuf, isClientside: Boolean) {
        val finalPayload = ByteBufAllocator.DEFAULT.ioBuffer(1 + compressed.readableBytes() + 8)
        finalPayload.writeByte(0xfe) // Wrapped packet ID

        val cipherPair = if (isClientside) clientCipher else serverCipher
        if (cipherPair != null) {
            val trailer = ByteBuffer.wrap(this.generateTrailer(compressed, cipherPair))
            val outBuffer = finalPayload.internalNioBuffer(1, compressed.readableBytes() + 8)
            val inBuffer = compressed.internalNioBuffer(compressed.readerIndex(), compressed.readableBytes())

            cipherPair.encryptionCipher.update(inBuffer, outBuffer)
            cipherPair.encryptionCipher.update(trailer, outBuffer)
            finalPayload.writerIndex(finalPayload.writerIndex() + compressed.readableBytes() + 8)
        } else {
            finalPayload.writeBytes(compressed)
        }
        if (isClientside) {
            clientsideSession.send(finalPayload)
        } else {
            if (serverState != RakNetState.CONNECTED) {
                pendingPackets.add(finalPayload)
            } else {
                serversideSession.send(finalPayload)
            }
        }
    }

    private fun readPacketFromBuffer(buffer: ByteBuf, isClientside: Boolean) {
        val packetId = buffer.readUnsignedByte().toInt()
        if (packetId == 0xfe && buffer.isReadable) {
            // Wrapper packet
            if (eventLoop.inEventLoop()) {
                this.onWrappedPacket(buffer, isClientside)
            } else {
                buffer.retain() // Handling on different thread
                eventLoop.execute {
                    try {
                        this.onWrappedPacket(buffer, isClientside)
                    } finally {
                        buffer.release()
                    }
                }
            }
        }
    }

    private fun onWrappedPacket(buffer: ByteBuf, isClientside: Boolean) {
        val cipherPair = if (isClientside) clientCipher else serverCipher
        if (cipherPair != null) {
            val inBuffer: ByteBuffer = buffer.internalNioBuffer(buffer.readerIndex(), buffer.readableBytes())
            val outBuffer = inBuffer.duplicate()
            cipherPair.decryptionCipher.update(inBuffer, outBuffer)

            buffer.writerIndex(buffer.writerIndex() - 8)
        }

        buffer.markReaderIndex()

        if (buffer.isReadable) {
            val packets = mutableListOf<BedrockPacket>()
            val data = readBuf(buffer)
            (if (isClientside) clientSerializer else serverSerializer).also {
                packetCodec.hasDecodeFailure = false
                val buf = Unpooled.copiedBuffer(data)
                it.deserialize(buf, packetCodec, packets, bedrockSession)
                buf.release()
            }
            if (packetCodec.hasDecodeFailure) {
                val buf = Unpooled.wrappedBuffer(data)
                sendSerialized(buf, !isClientside)
                log.warn("skipping packets because of failure whilst decode")
                return
            }
            packets.forEach {
                val hold = if (isClientside) {
                    listener.onPacketOutbound(it)
                } else {
                    listener.onPacketInbound(it)
                }
                if (!hold) return@forEach
                if (isClientside) {
                    outboundPacket(it)
                } else {
                    inboundPacket(it)
                }
            }
        }
    }

    internal inner class RakNetRelayClientListener : RakNetSessionListener {
        override fun onSessionChangeState(state: RakNetState) {
            clientState = state
            logInfo("client connection state: $state")
        }

        override fun onDisconnect(reason: DisconnectReason) {
            if (!serversideSession.isClosed) {
                serversideSession.disconnect(reason)
            }
            clientState = RakNetState.CONNECTED
        }

        override fun onEncapsulated(packet: EncapsulatedPacket) {
            if (clientState != RakNetState.CONNECTED) return
            readPacketFromBuffer(packet.buffer, true)
        }

        override fun onDirect(buf: ByteBuf) {}
    }

    internal inner class RakNetRelayServerListener : RakNetSessionListener {

        override fun onSessionChangeState(state: RakNetState) {
            serverState = state
            logInfo("server connection state: $state")
            // no need for waiting client, cuz client always sends the first packet
            if (state == RakNetState.CONNECTED && pendingPackets.isNotEmpty()) {
                logInfo("pending packets: ${pendingPackets.size}")
                pendingPackets.forEach {
                    serversideSession.send(it)
                }
                pendingPackets.clear()
            }
        }

        override fun onDisconnect(reason: DisconnectReason) {
            if (!clientsideSession.isClosed) {
                clientsideSession.disconnect(reason)
            }
            serverState = RakNetState.CONNECTED
        }

        override fun onEncapsulated(packet: EncapsulatedPacket) {
            if (serverState != RakNetState.CONNECTED) return
            readPacketFromBuffer(packet.buffer, false)
        }

        override fun onDirect(buf: ByteBuf) {}
    }

    companion object {
        private fun readBuf(buf: ByteBuf): ByteArray {
            val bytes = ByteArray(buf.readableBytes())
            val readerIndex = buf.readerIndex()
            buf.getBytes(readerIndex, bytes)
            return bytes
        }

        private fun safeSleep(interval: Long) {
            try {
                Thread.sleep(interval)
            } catch (_: InterruptedException) {
            }
        }
    }
}