/*******************************************************************************
 * Copyright (c) 2012, Institute for Pervasive Computing, ETH Zurich.
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. Neither the name of the Institute nor the names of its contributors
 *    may be used to endorse or promote products derived from this software
 *    without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE INSTITUTE AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE INSTITUTE OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS
 * OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY
 * OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 * SUCH DAMAGE.
 * 
 * This file is part of the Californium (Cf) CoAP framework.
 ******************************************************************************/
package ch.ethz.inf.vs.scandium.dtls;

import java.util.List;

import ch.ethz.inf.vs.scandium.util.DatagramReader;
import ch.ethz.inf.vs.scandium.util.DatagramWriter;

/**
 * The server will send this message in response to a {@link ClientHello}
 * message when it was able to find an acceptable set of algorithms. If it
 * cannot find such a match, it will respond with a handshake failure alert. See
 * <a href="http://tools.ietf.org/html/rfc5246#section-7.4.1.3">RFC 5246</a> for
 * further details.
 * 
 * @author Stefan Jucker
 * 
 */
public class ServerHello extends HandshakeMessage {

	// DTLS-specific constants ///////////////////////////////////////////

	private static final int VERSION_BITS = 8; // for major and minor each

	private static final int RANDOM_BYTES = 32;

	private static final int SESSION_ID_LENGTH_BITS = 8;

	private static final int CIPHER_SUITE_BITS = 16;

	private static final int COMPRESSION_METHOD_BITS = 8;

	// Members ///////////////////////////////////////////////////////////

	/**
	 * This field will contain the lower of that suggested by the client in the
	 * {@link ClientHello} and the highest supported by the server.
	 */
	private ProtocolVersion serverVersion;

	/**
	 * This structure is generated by the server and MUST be independently
	 * generated from the {@link ClientHello}.random.
	 */
	private Random random;

	/**
	 * This is the identity of the session corresponding to this connection.
	 */
	private SessionId sessionId;

	/**
	 * The single {@link CipherSuite} selected by the server from the list in
	 * {@link ClientHello}.cipher_suites.
	 */
	private CipherSuite cipherSuite;

	/**
	 * The single compression algorithm selected by the server from the list in
	 * ClientHello.compression_methods.
	 */
	private CompressionMethod compressionMethod;

	/**
	 * A list of extensions. Note that only extensions offered by the client can
	 * appear in the server's list.
	 */
	private HelloExtensions extensions = null;

	// Constructor ////////////////////////////////////////////////////

	/**
	 * Constructs a full ServerHello message. Only the HelloExtensions are
	 * optional. See <a
	 * href="http://tools.ietf.org/html/rfc5246#section-7.4.1.3">7.4.1.3. Server
	 * Hello</a> for details.
	 * 
	 * @param version
	 *            the negotiated version (highest supported by server).
	 * @param random
	 *            the server's random.
	 * @param sessionId
	 *            the new session's identifier.
	 * @param cipherSuite
	 *            the negotiated cipher suite.
	 * @param compressionMethod
	 *            the negotiated compression method.
	 * @param extensions
	 *            a list of extensions supported by the client (potentially
	 *            empty).
	 */
	public ServerHello(ProtocolVersion version, Random random, SessionId sessionId, CipherSuite cipherSuite, CompressionMethod compressionMethod, HelloExtensions extensions) {
		this.serverVersion = version;
		this.random = random;
		this.sessionId = sessionId;
		this.cipherSuite = cipherSuite;
		this.compressionMethod = compressionMethod;
		this.extensions = extensions;
	}

	// Serialization //////////////////////////////////////////////////

	@Override
	public byte[] fragmentToByteArray() {
		DatagramWriter writer = new DatagramWriter();

		writer.write(serverVersion.getMajor(), VERSION_BITS);
		writer.write(serverVersion.getMinor(), VERSION_BITS);

		writer.writeBytes(random.getRandomBytes());

		writer.write(sessionId.length(), SESSION_ID_LENGTH_BITS);
		writer.writeBytes(sessionId.getSessionId());

		writer.write(cipherSuite.getCode(), CIPHER_SUITE_BITS);
		writer.write(compressionMethod.getCode(), COMPRESSION_METHOD_BITS);

		if (extensions != null) {
			writer.writeBytes(extensions.toByteArray());
		}

		return writer.toByteArray();
	}

	public static HandshakeMessage fromByteArray(byte[] byteArray) throws HandshakeException {
		DatagramReader reader = new DatagramReader(byteArray);

		int major = reader.read(VERSION_BITS);
		int minor = reader.read(VERSION_BITS);
		ProtocolVersion version = new ProtocolVersion(major, minor);

		Random random = new Random(reader.readBytes(RANDOM_BYTES));

		int sessionIdLength = reader.read(SESSION_ID_LENGTH_BITS);
		SessionId sessionId = new SessionId(reader.readBytes(sessionIdLength));

		CipherSuite cipherSuite = CipherSuite.getTypeByCode(reader.read(CIPHER_SUITE_BITS));
		CompressionMethod compressionMethod = CompressionMethod.getMethodByCode(reader.read(COMPRESSION_METHOD_BITS));

		byte[] bytesLeft = reader.readBytesLeft();
		HelloExtensions extensions = null;
		if (bytesLeft.length > 0) {
			extensions = HelloExtensions.fromByteArray(bytesLeft);
		}

		return new ServerHello(version, random, sessionId, cipherSuite, compressionMethod, extensions);
	}

	// Methods ////////////////////////////////////////////////////////

	@Override
	public HandshakeType getMessageType() {
		return HandshakeType.SERVER_HELLO;
	}

	@Override
	public int getMessageLength() {

		/*
		 * if no extensions set, empty; otherwise 2 bytes for field length and
		 * then the length of the extensions. See
		 * http://tools.ietf.org/html/rfc5246#section-7.4.1.2
		 */
		int extensionsLength = (extensions != null) ? (2 + extensions.getLength()) : 0;

		/*
		 * fixed sizes: version (2) + random (32) + session ID length (1) +
		 * cipher suit (2) + compression method (1) = 38, variable sizes: session
		 * ID
		 */

		return 38 + sessionId.length() + extensionsLength;
	}

	public ProtocolVersion getServerVersion() {
		return serverVersion;
	}

	public void setServerVersion(ProtocolVersion serverVersion) {
		this.serverVersion = serverVersion;
	}

	public Random getRandom() {
		return random;
	}

	public void setRandom(Random random) {
		this.random = random;
	}

	public SessionId getSessionId() {
		return sessionId;
	}

	public void setSessionId(SessionId sessionId) {
		this.sessionId = sessionId;
	}

	public CipherSuite getCipherSuite() {
		return cipherSuite;
	}

	public void setCipherSuite(CipherSuite cipherSuite) {
		this.cipherSuite = cipherSuite;
	}

	public CompressionMethod getCompressionMethod() {
		return compressionMethod;
	}

	public void setCompressionMethod(CompressionMethod compressionMethod) {
		this.compressionMethod = compressionMethod;
	}
	
	public HelloExtensions getExtensions() {
		return extensions;
	}

	public void setExtensions(HelloExtensions extensions) {
		this.extensions = extensions;
	}
	
	/**
	 * Gets the server's 'cert-receive' extension if available. As described in
	 * <a href="http://tools.ietf.org/html/draft-ietf-tls-oob-pubkey-04">
	 * Out-of-Band Public Key Validation</a>.
	 * 
	 * @return the 'cert-receive' extension, if available, <code>null</code>
	 *         otherwise.
	 */
	public CertReceiveExtension getCertReceiveExtension() {
		if (extensions != null) {
			List<HelloExtension> exts = extensions.getExtensions();
			for (HelloExtension helloExtension : exts) {
				if (helloExtension instanceof CertReceiveExtension) {
					return (CertReceiveExtension) helloExtension;
				}
			}
		}
		return null;
	}
	
	/**
	 * 
	 * @return the 'cert-send' extension, if available, <code>null</code>
	 *         otherwise.
	 */
	public CertSendExtension getCertSendExtension() {
		if (extensions != null) {
			List<HelloExtension> exts = extensions.getExtensions();
			for (HelloExtension helloExtension : exts) {
				if (helloExtension instanceof CertSendExtension) {
					return (CertSendExtension) helloExtension;
				}
			}
		}
		return null;
	}
	
	/**
	 * 
	 * @return the certificate type extension if available, <code>null</code>
	 *         otherwise.
	 */
	public CertificateTypeExtension getCertificateTypeExtension() {
		if (extensions != null) {
			List<HelloExtension> exts = extensions.getExtensions();
			for (HelloExtension helloExtension : exts) {
				if (helloExtension instanceof CertificateTypeExtension) {
					return (CertificateTypeExtension) helloExtension;
				}
			}
		}
		return null;
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append(super.toString());
		sb.append("\t\tServer Version: " + serverVersion.getMajor() + ", " + serverVersion.getMinor() + "\n");
		sb.append("\t\tRandom: \n" + random.toString());
		sb.append("\t\tSession ID Length: " + sessionId.length() + "\n");
		if (sessionId.length() > 0) {
			sb.append("\t\tSession ID: " + ByteArrayUtils.toHexString(sessionId.getSessionId()) + "\n");
		}
		sb.append("\t\tCipher Suite: " + cipherSuite.toString() + "\n");
		sb.append("\t\tCompression Method: " + compressionMethod.toString() + "\n");
		
		if (extensions != null) {
			sb.append(extensions.toString());
		}

		return sb.toString();
	}

}