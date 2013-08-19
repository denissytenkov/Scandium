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
package ch.inf.vs.scandium.dtls;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.logging.Logger;

import ch.inf.vs.scandium.ScandiumLogger;
import ch.inf.vs.scandium.dtls.AlertMessage.AlertDescription;
import ch.inf.vs.scandium.dtls.AlertMessage.AlertLevel;
import ch.inf.vs.californium.network.serializer.DatagramReader;
import ch.inf.vs.californium.network.serializer.DatagramWriter;

/**
 * This message is used to provide explicit verification of a client
 * certificate. This message is only sent following a client certificate that
 * has signing capability (i.e., all certificates except those containing fixed
 * Diffie-Hellman parameters). When sent, it MUST immediately follow the
 * {@link ClientKeyExchange} message. For further details see <a
 * href="http://tools.ietf.org/html/rfc5246#section-7.4.8">RFC 5246</a>.
 * 
 * @author Stefan Jucker
 * 
 */
public class CertificateVerify extends HandshakeMessage {
	
	// Logging ///////////////////////////////////////////////////////////

	private static final Logger LOG = ScandiumLogger.getLogger(CertificateVerify.class);

	// DTLS-specific constants ////////////////////////////////////////

	private static final int HASH_ALGORITHM_BITS = 8;
	
	private static final int SIGNATURE_ALGORITHM_BITS = 8;

	private static final int SIGNATURE_LENGTH_BITS = 16;
	
	// Members ////////////////////////////////////////////////////////

	/** The digitally signed handshake messages. */
	private byte[] signatureBytes;
	
	/** The signature and hash algorithm which must be included into the digitally-signed struct. */
	private SignatureAndHashAlgorithm signatureAndHashAlgorithm;

	// Constructor ////////////////////////////////////////////////////
	
	/**
	 * Called by client to create its CertificateVerify message.
	 * 
	 * @param signatureAndHashAlgorithm
	 *            the signature and hash algorithm used to create the signature.
	 * @param clientPrivateKey
	 *            the client's private key to sign the signature.
	 * @param handshakeMessages
	 *            the handshake messages which are signed.
	 */
	public CertificateVerify(SignatureAndHashAlgorithm signatureAndHashAlgorithm, PrivateKey clientPrivateKey, byte[] handshakeMessages) {
		this.signatureAndHashAlgorithm = signatureAndHashAlgorithm;
		this.signatureBytes = setSignature(clientPrivateKey, handshakeMessages);
	}

	/**
	 * Called by the server when receiving the client's CertificateVerify
	 * message.
	 * 
	 * @param signatureAndHashAlgorithm
	 *            the signature and hash algorithm used to verify the signature.
	 * @param signatureBytes
	 *            the signature.
	 */
	public CertificateVerify(SignatureAndHashAlgorithm signatureAndHashAlgorithm, byte[] signatureBytes) {
		this.signatureAndHashAlgorithm = signatureAndHashAlgorithm;
		this.signatureBytes = signatureBytes;
	}

	// Methods ////////////////////////////////////////////////////////

	@Override
	public HandshakeType getMessageType() {
		return HandshakeType.CERTIFICATE_VERIFY;
	}

	@Override
	public int getMessageLength() {
		/*
		 * fixed: signature and hash algorithm (2 bytes) + signature length field (2 bytes), see
		 * http://tools.ietf.org/html/rfc5246#section-4.7
		 */
		return 4 + signatureBytes.length;
	}

	// Serialization //////////////////////////////////////////////////

	@Override
	public byte[] fragmentToByteArray() {
		DatagramWriter writer = new DatagramWriter();

		// according to http://tools.ietf.org/html/rfc5246#section-4.7 the
		// signature algorithm must also be included
		writer.write(signatureAndHashAlgorithm.getHash().getCode(), HASH_ALGORITHM_BITS);
		writer.write(signatureAndHashAlgorithm.getSignature().getCode(), SIGNATURE_ALGORITHM_BITS);

		writer.write(signatureBytes.length, SIGNATURE_LENGTH_BITS);
		writer.writeBytes(signatureBytes);

		return writer.toByteArray();
	}

	public static HandshakeMessage fromByteArray(byte[] byteArray) {
		DatagramReader reader = new DatagramReader(byteArray);

		// according to http://tools.ietf.org/html/rfc5246#section-4.7 the
		// signature algorithm must also be included
		int hashAlgorithm = reader.read(HASH_ALGORITHM_BITS);
		int signatureAlgorithm = reader.read(SIGNATURE_ALGORITHM_BITS);
		SignatureAndHashAlgorithm signAndHash = new SignatureAndHashAlgorithm(hashAlgorithm, signatureAlgorithm);

		int length = reader.read(SIGNATURE_LENGTH_BITS);
		byte[] signature = reader.readBytes(length);

		return new CertificateVerify(signAndHash, signature);
	}
	
	// Methods ////////////////////////////////////////////////////////
	
	/**
	 * Creates the signature and signs it with the client's private key.
	 * 
	 * @param clientPrivateKey
	 *            the client's private key.
	 * @param handshakeMessages
	 *            the handshake messages used up to now in the handshake.
	 * @return the signature.
	 */
	private byte[] setSignature(PrivateKey clientPrivateKey, byte[] handshakeMessages) {
		signatureBytes = new byte[] {};

		try {
			Signature signature = Signature.getInstance(signatureAndHashAlgorithm.toString());
			signature.initSign(clientPrivateKey);

			signature.update(handshakeMessages);

			signatureBytes = signature.sign();
		} catch (Exception e) {
			LOG.severe("Could not create signature.");
			e.printStackTrace();
		}

		return signatureBytes;
	}
	
	/**
	 * Tries to verify the client's signature contained in the CertificateVerify
	 * message.
	 * 
	 * @param clientPublicKey
	 *            the client's public key.
	 * @param handshakeMessages
	 *            the handshake messages exchanged so far.
	 * @throws HandshakeException if the signature could not be verified.
	 */
	public void verifySignature(PublicKey clientPublicKey, byte[] handshakeMessages) throws HandshakeException {
		boolean verified = false;
		try {
			Signature signature = Signature.getInstance(signatureAndHashAlgorithm.toString());
			signature.initVerify(clientPublicKey);

			signature.update(handshakeMessages);

			verified = signature.verify(signatureBytes);

		} catch (Exception e) {
			LOG.severe("Could not verify the client's signature.");
			e.printStackTrace();
		}
		
		if (!verified) {
			String message = "The client's CertificateVerify message could not be verified.";
			AlertMessage alert = new AlertMessage(AlertLevel.FATAL, AlertDescription.HANDSHAKE_FAILURE);
			throw new HandshakeException(message, alert);
		}
	}

}
