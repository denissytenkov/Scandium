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

import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import ch.ethz.inf.vs.scandium.dtls.AlertMessage.AlertDescription;
import ch.ethz.inf.vs.scandium.dtls.AlertMessage.AlertLevel;
import ch.ethz.inf.vs.scandium.dtls.CertificateRequest.ClientCertificateType;
import ch.ethz.inf.vs.scandium.dtls.CertificateRequest.HashAlgorithm;
import ch.ethz.inf.vs.scandium.dtls.CertificateRequest.SignatureAlgorithm;
import ch.ethz.inf.vs.scandium.dtls.CertificateTypeExtension.CertificateType;
import ch.ethz.inf.vs.scandium.dtls.CipherSuite.KeyExchangeAlgorithm;
import ch.ethz.inf.vs.scandium.dtls.SupportedPointFormatsExtension.ECPointFormat;
import ch.ethz.inf.vs.scandium.util.ScProperties;

/**
 * Server handshaker does the protocol handshaking from the point of view of a
 * server. It is message-driven by the parent {@link Handshaker} class.
 * 
 * @author Stefan Jucker
 * 
 */
public class ServerHandshaker extends Handshaker {

	// Members ////////////////////////////////////////////////////////

	/** Is the client required to authenticate itself? */
	private boolean clientAuthenticationRequired = ScProperties.std.getBool("CLIENT_AUTHENTICATION");

	/**
	 * The client's public key from its certificate (only sent when
	 * CertificateRequest sent).
	 */
	private PublicKey clientPublicKey;

	private List<CipherSuite> supportedCipherSuites;

	/*
	 * Store all the messages which can possibly be sent by the client. We
	 * need these to compute the handshake hash.
	 */
	/** The client's {@link ClientHello}. Mandatory. */
	protected ClientHello clientHello;
	/** The client's {@link CertificateMessage}. Optional. */
	protected CertificateMessage clientCertificate = null;
	/** The client's {@link ClientKeyExchange}. mandatory. */
	protected ClientKeyExchange clientKeyExchange;
	/** The client's {@link CertificateVerify}. Optional. */
	protected CertificateVerify certificateVerify = null;
	/** The client's {@link Finished} message. Mandatory. */
	protected Finished clientFinished;

	// Constructors ///////////////////////////////////////////////////

	/**
	 * 
	 * @param endpointAddress
	 *            the peer's address.
	 * @param session
	 *            the {@link DTLSSession}.
	 */
	public ServerHandshaker(InetSocketAddress endpointAddress, DTLSSession session) {
		super(endpointAddress, false, session);

		this.supportedCipherSuites = new ArrayList<CipherSuite>();
		this.supportedCipherSuites.add(CipherSuite.SSL_NULL_WITH_NULL_NULL);
		this.supportedCipherSuites.add(CipherSuite.TLS_PSK_WITH_AES_128_CCM_8);
		this.supportedCipherSuites.add(CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_CCM_8);
	}

	// Methods ////////////////////////////////////////////////////////

	@Override
	public synchronized DTLSFlight processMessage(Record record) throws HandshakeException {
		if (lastFlight != null) {
			// we already sent the last flight, but the client did not receive
			// it, since we received its finished message again, so we
			// retransmit our last flight
			LOG.info("Received client's (" + endpointAddress.toString() + ") finished message again, retransmit the last flight.");
			return lastFlight;
		}

		DTLSFlight flight = null;

		if (!processMessageNext(record)) {
			return flight;
		}

		switch (record.getType()) {
		case ALERT:
			AlertMessage alert = (AlertMessage) record.getFragment();
			switch (alert.getDescription()) {
			case CLOSE_NOTIFY:
				flight = closeConnection();
				break;
			case HANDSHAKE_FAILURE:
				// TODO react accordingly
				break;

			default:
				break;
			}
			break;

		case CHANGE_CIPHER_SPEC:
			record.getFragment();
			setCurrentReadState();
			session.incrementReadEpoch();
			break;

		case HANDSHAKE:
			HandshakeMessage fragment = (HandshakeMessage) record.getFragment();

			// check for fragmentation
			if (fragment instanceof FragmentedHandshakeMessage) {
				fragment = handleFragmentation((FragmentedHandshakeMessage) fragment);
				if (fragment == null) {
					// fragment could not yet be fully reassembled
					break;
				}
				// continue with the reassembled handshake message
				record.setFragment(fragment);
			}
			
			switch (fragment.getMessageType()) {
			case CLIENT_HELLO:
				flight = receivedClientHello((ClientHello) fragment);
				break;

			case CERTIFICATE:
				receivedClientCertificate((CertificateMessage) fragment);
				break;

			case CLIENT_KEY_EXCHANGE:
				byte[] premasterSecret;
				switch (keyExchange) {
				case PSK:
					premasterSecret = receivedClientKeyExchange((PSKClientKeyExchange) fragment);
					generateKeys(premasterSecret);
					break;

				case EC_DIFFIE_HELLMAN:
					premasterSecret = receivedClientKeyExchange((ECDHClientKeyExchange) fragment);
					generateKeys(premasterSecret);
					break;

				case NULL:
					premasterSecret = receivedClientKeyExchange((NULLClientKeyExchange) fragment);
					generateKeys(premasterSecret);
					break;

				default:
					AlertMessage alertMessage = new AlertMessage(AlertLevel.FATAL, AlertDescription.HANDSHAKE_FAILURE);
					throw new HandshakeException("Unknown key exchange algorithm: " + keyExchange, alertMessage);
				}
				handshakeMessages = ByteArrayUtils.concatenate(handshakeMessages, clientKeyExchange.toByteArray());
				break;

			case CERTIFICATE_VERIFY:
				receivedCertificateVerify((CertificateVerify) fragment);
				break;

			case FINISHED:
				flight = receivedClientFinished((Finished) fragment);
				break;

			default:
				AlertMessage alertMessage = new AlertMessage(AlertLevel.FATAL, AlertDescription.UNEXPECTED_MESSAGE);
				throw new HandshakeException("Server received unexpected handshake message:\n" + fragment.toString(), alertMessage);
			}

			break;

		default:
			AlertMessage alertMessage = new AlertMessage(AlertLevel.FATAL, AlertDescription.HANDSHAKE_FAILURE);
			throw new HandshakeException("Server received not supported record:\n" + record.toString(), alertMessage);
		}
		
		if (flight == null) {
			Record nextMessage = null;
			// check queued message, if it is now their turn
			for (Record queuedMessage : queuedMessages) {
				if (processMessageNext(queuedMessage)) {
					// queuedMessages.remove(queuedMessage);
					nextMessage = queuedMessage;
					break;
				}
			}
			if (nextMessage != null) {
				flight = processMessage(nextMessage);
			}
		}
		LOG.info("DTLS Message processed (" + endpointAddress.toString() + "):\n" + record.toString());
		return flight;
	}
	
	/**
	 * If the server requires mutual authentication, the client must send its
	 * certificate.
	 * 
	 * @param message
	 *            the client's {@link CertificateMessage}.
	 * @throws HandshakeException
	 *             if the certificate could not be verified.
	 */
	private void receivedClientCertificate(CertificateMessage message) throws HandshakeException {
		if (clientCertificate != null && (clientCertificate.getMessageSeq() == message.getMessageSeq())) {
			// discard duplicate message
			return;
		}

		clientCertificate = message;
		clientCertificate.verifyCertificate(loadTrustedCertificates());
		clientPublicKey = clientCertificate.getPublicKey();
		
		handshakeMessages = ByteArrayUtils.concatenate(handshakeMessages, clientCertificate.toByteArray());
	}

	/**
	 * Verifies the clien's CertificateVerify message and if verification fails,
	 * aborts and sends Alert message.
	 * 
	 * @param message
	 *            the client's CertificateVerify.
	 * @return <code>null</code> if the signature can be verified, otherwise a
	 *         flight containing an Alert.
	 * @throws HandshakeException 
	 */
	private void receivedCertificateVerify(CertificateVerify message) throws HandshakeException {
		certificateVerify = message;

		message.verifySignature(clientPublicKey, handshakeMessages);
	}

	/**
	 * Called, when the server received the client's {@link Finished} message.
	 * Generate a {@link DTLSFlight} containing the
	 * {@link ChangeCipherSpecMessage} and {@link Finished} message. This flight
	 * will not be retransmitted, unless we receive the same finish message in
	 * the future; then, we retransmit this flight.
	 * 
	 * @param message
	 *            the client's {@link Finished} message.
	 * @return the server's last {@link DTLSFlight}.
	 * @throws HandshakeException 
	 */
	private DTLSFlight receivedClientFinished(Finished message) throws HandshakeException {
		if (lastFlight != null) {
			return null;
		}
		
		// check if client sent all expected messages
		// (i.e. ClientCertificate/CertificateVerify when server sent CertificateRequest)
		if (keyExchange == CipherSuite.KeyExchangeAlgorithm.EC_DIFFIE_HELLMAN && 
				clientAuthenticationRequired && 
				(clientCertificate == null || certificateVerify == null)) {
			AlertMessage alert = new AlertMessage(AlertLevel.FATAL, AlertDescription.HANDSHAKE_FAILURE);
			throw new HandshakeException("Client did not send required authentication messages.", alert);
		}

		DTLSFlight flight = new DTLSFlight();
		clientFinished = message;

		// create handshake hash
		if (clientCertificate != null) { // optional
			md.update(clientCertificate.toByteArray());
		}

		md.update(clientKeyExchange.toByteArray()); // mandatory

		if (certificateVerify != null) { // optional
			md.update(certificateVerify.toByteArray());
		}

		MessageDigest mdWithClientFinished = null;
		try {
			/*
			 * the handshake_messages for the Finished message sent by the
			 * client will be different from that for the Finished message sent
			 * by the server, because the one that is sent second will include
			 * the prior one.
			 */
			mdWithClientFinished = (MessageDigest) md.clone();
			mdWithClientFinished.update(clientFinished.toByteArray());
		} catch (CloneNotSupportedException e) {
			LOG.severe("Clone not supported.");
			e.printStackTrace();
		}

		// Verify client's data
		byte[] handshakeHash = md.digest();
		clientFinished.verifyData(getMasterSecret(), true, handshakeHash);

		/*
		 * First, send ChangeCipherSpec
		 */
		ChangeCipherSpecMessage changeCipherSpecMessage = new ChangeCipherSpecMessage();
		flight.addMessage(wrapMessage(changeCipherSpecMessage));
		setCurrentWriteState();
		session.incrementWriteEpoch();

		/*
		 * Second, send Finished message
		 */
		handshakeHash = mdWithClientFinished.digest();
		Finished finished = new Finished(getMasterSecret(), isClient, handshakeHash);
		flight.addMessage(wrapMessage(finished));

		state = HandshakeType.FINISHED.getCode();
		session.setActive(true);

		flight.setRetransmissionNeeded(false);
		// store, if we need to retransmit this flight, see
		// http://tools.ietf.org/html/rfc6347#section-4.2.4
		lastFlight = flight;
		return flight;

	}

	/**
	 * Called after the server receives a {@link ClientHello} handshake message.
	 * If the message has a {@link Cookie} set, verify it and prepare the next
	 * flight (mandatory messages depend on the cipher suite / key exchange
	 * algorithm). Mandatory messages are ServerHello and ServerHelloDone; see
	 * <a href="http://tools.ietf.org/html/rfc5246#section-7.3">Figure 1.
	 * Message flow for a full handshake</a> for details about the messages in
	 * the next flight.
	 * 
	 * @param message
	 *            the client's hello message.
	 * @return the server's next flight to be sent.
	 * @throws HandshakeException
	 */
	private DTLSFlight receivedClientHello(ClientHello message) throws HandshakeException {
		DTLSFlight flight = new DTLSFlight();

		if (message.getCookie().length() > 0 && isValidCookie(message)) {
			// client has set a cookie, so it is a response to
			// HelloVerifyRequest

			// store the message and update the handshake hash
			clientHello = message;
			md.update(clientHello.toByteArray());
			handshakeMessages = ByteArrayUtils.concatenate(handshakeMessages, clientHello.toByteArray());

			/*
			 * First, send ServerHello (mandatory)
			 */
			ProtocolVersion serverVersion = negotiateProtocolVersion(clientHello.getClientVersion());

			// store client and server random
			clientRandom = message.getRandom();
			serverRandom = new Random(new SecureRandom());

			SessionId sessionId = new SessionId();
			session.setSessionIdentifier(sessionId);

			CipherSuite cipherSuite = negotiateCipherSuite(clientHello.getCipherSuites());
			setCipherSuite(cipherSuite);

			// currently only NULL compression supported, no negotiation needed
			CompressionMethod compressionMethod = CompressionMethod.NULL;
			setCompressionMethod(compressionMethod);
			
			
			HelloExtensions extensions = null;
			ClientCertificateTypeExtension clientCertificateTypeExtension = clientHello.getClientCertificateTypeExtension();
			if (clientCertificateTypeExtension != null) {
				// choose certificate type from client's list
				CertificateType certType = negotiateCertificateType(clientCertificateTypeExtension);
				extensions = new HelloExtensions();
				// the certificate type requested from the client
				CertificateTypeExtension ext1 = new ClientCertificateTypeExtension(false);
				ext1.addCertificateType(certType);
				
				extensions.addExtension(ext1);
				
				if (certType == CertificateType.RAW_PUBLIC_KEY) {
					session.setReceiveRawPublicKey(true);
				}
			}
			
			CertificateTypeExtension serverCertificateTypeExtension = clientHello.getServerCertificateTypeExtension();
			if (serverCertificateTypeExtension != null) {
				// choose certificate type from client's list
				CertificateType certType = negotiateCertificateType(serverCertificateTypeExtension);
				if (extensions == null) {
					extensions = new HelloExtensions();
				}
				// the certificate type found in the attached certificate payload
				CertificateTypeExtension ext2 = new ServerCertificateTypeExtension(false);
				ext2.addCertificateType(certType);
				
				extensions.addExtension(ext2);
				
				if (certType == CertificateType.RAW_PUBLIC_KEY) {
					session.setSendRawPublicKey(true);
				}
			}
			
			
			if (keyExchange == CipherSuite.KeyExchangeAlgorithm.EC_DIFFIE_HELLMAN) {
				// if we chose a ECC cipher suite, the server should send the supported point formats extension in its ServerHello
				List<ECPointFormat> formats = Arrays.asList(ECPointFormat.UNCOMPRESSED);
				
				if (extensions == null) {
					extensions = new HelloExtensions();
				}
				HelloExtension ext3 = new SupportedPointFormatsExtension(formats);
				extensions.addExtension(ext3);
			}
			
			
			ServerHello serverHello = new ServerHello(serverVersion, serverRandom, sessionId, cipherSuite, compressionMethod, extensions);
			flight.addMessage(wrapMessage(serverHello));
			
			// update the handshake hash
			md.update(serverHello.toByteArray());
			handshakeMessages = ByteArrayUtils.concatenate(handshakeMessages, serverHello.toByteArray());

			/*
			 * Second, send Certificate (if required by key exchange algorithm)
			 */
			CertificateMessage certificate = null;
			switch (keyExchange) {
			case EC_DIFFIE_HELLMAN:
				certificate = new CertificateMessage(certificates, session.sendRawPublicKey());
				break;

			default:
				// NULL and PSK do not require the Certificate message
				// See http://tools.ietf.org/html/rfc4279#section-2
				break;
			}
			if (certificate != null) {
				flight.addMessage(wrapMessage(certificate));
				md.update(certificate.toByteArray());
				handshakeMessages = ByteArrayUtils.concatenate(handshakeMessages, certificate.toByteArray());
			}

			/*
			 * Third, send ServerKeyExchange (if required by key exchange
			 * algorithm)
			 */
			ServerKeyExchange serverKeyExchange = null;
			switch (keyExchange) {
			case EC_DIFFIE_HELLMAN:
				int namedCurveId = negotiateNamedCurve(clientHello.getSupportedEllipticCurvesExtension());
				ecdhe = new ECDHECryptography(namedCurveId);
				serverKeyExchange = new ECDHServerKeyExchange(ecdhe, privateKey, clientRandom, serverRandom, namedCurveId);
				break;

			case PSK:
				// serverKeyExchange = new PSKServerKeyExchange("TEST");
				break;

			default:
				// NULL does not require the server's key exchange message
				break;
			}
			if (serverKeyExchange != null) {
				flight.addMessage(wrapMessage(serverKeyExchange));
				md.update(serverKeyExchange.toByteArray());
				handshakeMessages = ByteArrayUtils.concatenate(handshakeMessages, serverKeyExchange.toByteArray());
			}

			/*
			 * Fourth, send CertificateRequest for client (if required)
			 */
			if (clientAuthenticationRequired && keyExchange != KeyExchangeAlgorithm.PSK) {

				CertificateRequest certificateRequest = new CertificateRequest();
				
				// TODO make this variable, reasonable values
				certificateRequest.addCertificateType(ClientCertificateType.ECDSA_SIGN);
				certificateRequest.addSignatureAlgorithm(new SignatureAndHashAlgorithm(HashAlgorithm.SHA256, SignatureAlgorithm.ECDSA));
				certificateRequest.addCertificateAuthorities(loadTrustedCertificates());

				flight.addMessage(wrapMessage(certificateRequest));
				md.update(certificateRequest.toByteArray());
				handshakeMessages = ByteArrayUtils.concatenate(handshakeMessages, certificateRequest.toByteArray());
			}

			/*
			 * Last, send ServerHelloDone (mandatory)
			 */
			ServerHelloDone serverHelloDone = new ServerHelloDone();
			flight.addMessage(wrapMessage(serverHelloDone));
			md.update(serverHelloDone.toByteArray());
			handshakeMessages = ByteArrayUtils.concatenate(handshakeMessages, serverHelloDone.toByteArray());

		} else {
			// either first time, or cookies did not match
			HelloVerifyRequest helloVerifyRequest = new HelloVerifyRequest(new ProtocolVersion(), generateCookie(message));
			flight.addMessage(wrapMessage(helloVerifyRequest));
		}
		return flight;
	}

	/**
	 * Generates the premaster secret by taking the client's public key and
	 * running the ECDHE key agreement.
	 * 
	 * @param message
	 *            the client's key exchange message.
	 * @return the premaster secret
	 */
	private byte[] receivedClientKeyExchange(ECDHClientKeyExchange message) {
		clientKeyExchange = message;
		byte[] premasterSecret = ecdhe.getSecret(message.getEncodedPoint()).getEncoded();

		return premasterSecret;
	}

	/**
	 * Retrieves the preshared key from the identity hint and then generates the
	 * premaster secret.
	 * 
	 * @param message
	 *            the client's key exchange message.
	 * @return the premaster secret
	 * @throws HandshakeException
	 *             if no specified preshared key available.
	 */
	private byte[] receivedClientKeyExchange(PSKClientKeyExchange message) throws HandshakeException {
		clientKeyExchange = message;

		// use the client's PSK identity to get right preshared key
		String identity = message.getIdentity();

		byte[] psk = sharedKeys.get(identity);
		LOG.fine("Received client's (" + endpointAddress.toString() + ") key exchange message for PSK:\nIdentity: " + identity + "\nPreshared Key: " + ByteArrayUtils.toHexString(psk));
		
		if (psk == null) {
			AlertMessage alert = new AlertMessage(AlertLevel.FATAL, AlertDescription.HANDSHAKE_FAILURE);
			throw new HandshakeException("No preshared secret found for identity: " + identity, alert);
		}
		
		return generatePremasterSecretFromPSK(psk);
	}

	/**
	 * Returns an empty premaster secret.
	 * 
	 * @param message
	 *            the client's key exchange message.
	 * @return the premaster secret
	 */
	private byte[] receivedClientKeyExchange(NULLClientKeyExchange message) {
		clientKeyExchange = message;

		// by current assumption we take an empty premaster secret
		// to compute the master secret and the resulting keys
		return new byte[] {};
	}

	/**
	 * Generates a cookie in such a way that they can be verified without
	 * retaining any per-client state on the server.
	 * 
	 * <pre>
	 * Cookie = HMAC(Secret, Client - IP, Client - Parameters)
	 * </pre>
	 * 
	 * as suggested <a
	 * href="http://tools.ietf.org/html/rfc6347#section-4.2.1">here</a>.
	 * 
	 * @return the cookie generated from the client's parameters.
	 */
	private Cookie generateCookie(ClientHello clientHello) {

		MessageDigest md;
		byte[] cookie = null;

		try {
			md = MessageDigest.getInstance("SHA-256");

			// Cookie = HMAC(Secret, Client-IP, Client-Parameters)
			byte[] secret = "generate cookie".getBytes();

			// Client-IP
			md.update(endpointAddress.toString().getBytes());

			// Client-Parameters
			md.update((byte) clientHello.getClientVersion().getMajor());
			md.update((byte) clientHello.getClientVersion().getMinor());
			md.update(clientHello.getRandom().getRandomBytes());
			md.update(clientHello.getSessionId().getSessionId());
			md.update(CipherSuite.listToByteArray(clientHello.getCipherSuites()));
			md.update(CompressionMethod.listToByteArray(clientHello.getCompressionMethods()));

			byte[] data = md.digest();

			cookie = Handshaker.doHMAC(md, secret, data);
		} catch (NoSuchAlgorithmException e) {
			LOG.info("Could not instantiate message digest algorithm.");
			e.printStackTrace();
		}
		if (cookie == null) {
			return new Cookie(new Random(new SecureRandom()).getRandomBytes());
		} else {
			return new Cookie(cookie);
		}
	}

	/**
	 * Checks whether the Cookie in the client's hello message matches the
	 * expected cookie generated from the client's parameters.
	 * 
	 * @param clientHello
	 *            the client's hello message containing the cookie.
	 * @return <code>true</code> if the cookie matches, <code>false</code>
	 *         otherwise.
	 */
	private boolean isValidCookie(ClientHello clientHello) {
		Cookie expected = generateCookie(clientHello);
		Cookie actual = clientHello.getCookie();
		boolean valid = Arrays.equals(expected.getCookie(), actual.getCookie());

		if (!valid) {
			LOG.info("Client's (" + endpointAddress.toString() + ") cookie did not match expected cookie:\n" + "Expected: " + ByteArrayUtils.toHexString(expected.getCookie()) + "\n" + "Actual: " + ByteArrayUtils.toHexString(actual.getCookie()));
		}

		return valid;
	}

	@Override
	public DTLSFlight getStartHandshakeMessage() {
		HelloRequest helloRequest = new HelloRequest();

		DTLSFlight flight = new DTLSFlight();
		flight.addMessage(wrapMessage(helloRequest));
		return flight;
	}

	/**
	 * Negotiates the version to be used. It will return the lower of that
	 * suggested by the client in the client hello and the highest supported by
	 * the server.
	 * 
	 * @param clientVersion
	 *            the suggested version by the client.
	 * @return the version to be used in the handshake.
	 * @throws HandshakeException
	 *             if the client's version is smaller than DTLS 1.2
	 */
	private ProtocolVersion negotiateProtocolVersion(ProtocolVersion clientVersion) throws HandshakeException {
		ProtocolVersion version = new ProtocolVersion();
		if (clientVersion.compareTo(version) >= 0) {
			return new ProtocolVersion();
		} else {
			AlertMessage alert = new AlertMessage(AlertLevel.FATAL, AlertDescription.PROTOCOL_VERSION);
			throw new HandshakeException("The server only supports DTLS v1.2", alert);
		}
	}

	/**
	 * Selects one of the client's proposed cipher suites.
	 * 
	 * @param cipherSuites
	 *            the client's cipher suites.
	 * @return The single cipher suite selected by the server from the list
	 *         which will be used after handshake completion.
	 * @throws HandshakeException
	 *             if no suitable cipher suite can be found.
	 */
	private CipherSuite negotiateCipherSuite(List<CipherSuite> cipherSuites) throws HandshakeException {
		// the client's list is sorted by preference
		for (CipherSuite cipherSuite : cipherSuites) {
			if (supportedCipherSuites.contains(cipherSuite) && cipherSuite != CipherSuite.SSL_NULL_WITH_NULL_NULL) {
				return cipherSuite;
			}
		}
		// if none of the client's proposed cipher suites matches throw exception
		AlertMessage alert = new AlertMessage(AlertLevel.FATAL, AlertDescription.HANDSHAKE_FAILURE);
		throw new HandshakeException("No supported cipher suite proposed by the client", alert);
	}

	/**
	 * Chooses a elliptic curve from the client's supported list.
	 * 
	 * @param extension
	 *            the supported elliptic curves extension.
	 * @return the chosen elliptic curve identifier.
	 * @throws HandshakeException
	 *             if no extension present in the ClientHello.
	 */
	private int negotiateNamedCurve(SupportedEllipticCurvesExtension extension) throws HandshakeException {
		if (extension != null) {
			for (Integer curveID : extension.getEllipticCurveList()) {
				// choose first proposal which is supported
				if (ECDHServerKeyExchange.NAMED_CURVE_PARAMETERS.get(curveID) != null) {
					return curveID;
				}
			}
		} else {
			// extension was not present in ClientHello, we can't continue the handshake
			AlertMessage alert = new AlertMessage(AlertLevel.FATAL, AlertDescription.HANDSHAKE_FAILURE);
			throw new HandshakeException("The client did not provide the supported elliptic curves extension although ECC cipher suite chosen.", alert);
		}
		AlertMessage alert = new AlertMessage(AlertLevel.FATAL, AlertDescription.HANDSHAKE_FAILURE);
		throw new HandshakeException("No proposed elliptic curve supported.", alert);

	}
	
	/**
	 * Chooses the certificate type which will be used in the rest of the
	 * handshake.
	 * 
	 * @param extension
	 *            the certificate type extension.
	 * @return the certificate type in which the client will send its
	 *         certificate.
	 */
	private CertificateType negotiateCertificateType(CertificateTypeExtension extension) {
		CertificateType certType = CertificateType.X_509;
		for (CertificateType type : extension.getCertificateTypes()) {
			return type;
		}
		return certType;
	}

}
