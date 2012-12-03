/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;

import net.i2p.util.NativeBigInteger;
import freenet.crypt.BlockCipher;
import freenet.crypt.DHGroup;
import freenet.crypt.DSA;
import freenet.crypt.DSAGroup;
import freenet.crypt.DSASignature;
import freenet.crypt.DiffieHellman;
import freenet.crypt.DiffieHellmanLightContext;
import freenet.crypt.ECDH;
import freenet.crypt.ECDHLightContext;
import freenet.crypt.Global;
import freenet.crypt.HMAC;
import freenet.crypt.KeyAgreementSchemeContext;
import freenet.crypt.PCFBMode;
import freenet.crypt.SHA256;
import freenet.crypt.UnsupportedCipherException;
import freenet.crypt.Util;
import freenet.crypt.ciphers.Rijndael;
import freenet.io.AddressTracker;
import freenet.io.AddressTracker.Status;
import freenet.io.comm.FreenetInetAddress;
import freenet.io.comm.IncomingPacketFilter.DECODED;
import freenet.io.comm.Peer;
import freenet.io.comm.Peer.LocalAddressException;
import freenet.io.comm.PeerContext;
import freenet.io.comm.PeerParseException;
import freenet.io.comm.ReferenceSignatureVerificationException;
import freenet.io.comm.SocketHandler;
import freenet.l10n.NodeL10n;
import freenet.node.OpennetManager.ConnectionType;
import freenet.node.useralerts.SimpleUserAlert;
import freenet.node.useralerts.UserAlert;
import freenet.node.useralerts.UserAlertManager;
import freenet.pluginmanager.MalformedPluginAddressException;
import freenet.pluginmanager.PacketTransportPlugin;
import freenet.pluginmanager.PluginAddress;
import freenet.pluginmanager.UnsupportedIPAddressOperationException;
import freenet.support.ByteArrayWrapper;
import freenet.support.Fields;
import freenet.support.HexUtil;
import freenet.support.LRUMap;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.SimpleFieldSet;
import freenet.support.TimeUtil;
import freenet.support.io.FileUtil;
import freenet.transports.PluginAddressComparator;
import freenet.support.io.NativeThread;

/**
 * @author amphibian
 *
 * Encodes and decodes packets for FNP.
 *
 * This includes encryption, authentication, and may later
 * include queueing etc. (that may require some interface
 * changes in IncomingPacketFilter).
 */
public class FNPPacketMangler implements OutgoingPacketMangler {
	private static volatile boolean logMINOR;
	private static volatile boolean logDEBUG;

	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback() {
			@Override
			public void shouldUpdate() {
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
				logDEBUG = Logger.shouldLog(LogLevel.DEBUG, this);
			}
		});
	}

	private final Node node;
	private final NodeCrypto crypto;
	/*
	 * This object was of type PacketSocketHandler, but now PacketTransportPlugin extends PacketSocketHandler.
	 */
	private final PacketTransportPlugin sock;
	protected final String transportName;
	/**
	 * Objects cached during JFK message exchange: JFK(3,4) with authenticator as key
	 * The messages are cached in hashmaps because the message retrieval from the cache
	 * can be performed in constant time( given the key)
	 */
	private final HashMap<ByteArrayWrapper, byte[]> authenticatorCache;
	/** The following is used in the HMAC calculation of JFK message3 and message4 */
	private static final byte[] JFK_PREFIX_INITIATOR, JFK_PREFIX_RESPONDER;
	static {
		byte[] I = null,R = null;
		try {
			I = "I".getBytes("UTF-8");
			R = "R".getBytes("UTF-8");
		} catch (UnsupportedEncodingException e) {
			throw new Error("Impossible: JVM doesn't support UTF-8: " + e, e);
		}

		JFK_PREFIX_INITIATOR = I;
		JFK_PREFIX_RESPONDER = R;
	}

	/* How often shall we generate a new exponential and add it to the FIFO? */
	public final static int DH_GENERATION_INTERVAL = 30000; // 30sec
	/* How big is the FIFO? */
	public final static int DH_CONTEXT_BUFFER_SIZE = 20;
	/*
	* The FIFO itself
	* Get a lock on dhContextFIFO before touching it!
	*/
	private final LinkedList<DiffieHellmanLightContext> dhContextFIFO = new LinkedList<DiffieHellmanLightContext>();
	/* The element which is about to be prunned from the FIFO */
	private DiffieHellmanLightContext dhContextToBePrunned = null;
	private static final DHGroup dhGroupToUse = Global.DHgroupA;
	private long jfkDHLastGenerationTimestamp = 0;
	
	private final LinkedList<ECDHLightContext> ecdhContextFIFO = new LinkedList<ECDHLightContext>();
    private ECDHLightContext ecdhContextToBePrunned;
    private static final ECDH.Curves ecdhCurveToUse = ECDH.Curves.P256;
	private long jfkECDHLastGenerationTimestamp = 0;

	protected static final int NONCE_SIZE = 8;
	private static final int RANDOM_BYTES_LENGTH = 12;
	private static final int HASH_LENGTH = SHA256.getDigestLength();
	/** The size of the key used to authenticate the hmac */
	private static final int TRANSIENT_KEY_SIZE = HASH_LENGTH;
	/** The key used to authenticate the hmac */
	private final byte[] transientKey = new byte[TRANSIENT_KEY_SIZE];
	public static final int TRANSIENT_KEY_REKEYING_MIN_INTERVAL = 30*60*1000;
	/** The rekeying interval for the session key (keytrackers) */
	public static final int SESSION_KEY_REKEYING_INTERVAL = 60*60*1000;
	/** The max amount of time we will accept to use the current tracker when it should have been replaced */
	public static final int MAX_SESSION_KEY_REKEYING_DELAY = 5*60*1000;
	/** The amount of data sent before we ask for a rekey */
	public static final int AMOUNT_OF_BYTES_ALLOWED_BEFORE_WE_REKEY = 1024 * 1024 * 1024;
	/** The Runnable in charge of rekeying on a regular basis */
	private final Runnable transientKeyRekeyer = new Runnable() {
		@Override
		public void run() {
			maybeResetTransientKey();
		}
	};
	/** Minimum headers overhead */
	private static final int HEADERS_LENGTH_MINIMUM =
		4 + // sequence number
		RANDOM_BYTES_LENGTH + // random junk
		1 + // version
		1 + // assume seqno != -1; otherwise would be 4
		4 + // other side's seqno
		1 + // number of acks
		0 + // assume no acks
		1 + // number of resend reqs
		0 + // assume no resend requests
		1 + // number of ack requests
		0 + // assume no ack requests
		1 + // no forgotten packets
		HASH_LENGTH + // hash
		1; // number of messages
	/** Headers overhead if there is one message and no acks. */
	static public final int HEADERS_LENGTH_ONE_MESSAGE =
		HEADERS_LENGTH_MINIMUM + 2; // 2 bytes = length of message. rest is the same.

	final int fullHeadersLengthMinimum;
	final int fullHeadersLengthOneMessage;
        
        private long lastConnectivityStatusUpdate;
        private Status lastConnectivityStatus;


	public FNPPacketMangler(Node node, NodeCrypto crypt, PacketTransportPlugin sock) {
		this.node = node;
		this.crypto = crypt;
		this.sock = sock;
		this.transportName = sock.transportName;
		authenticatorCache = new HashMap<ByteArrayWrapper, byte[]>();
		fullHeadersLengthMinimum = HEADERS_LENGTH_MINIMUM + sock.getHeadersLength();
		fullHeadersLengthOneMessage = HEADERS_LENGTH_ONE_MESSAGE + sock.getHeadersLength();
	}

	@Override
	public void startMangler() {
		// Run it directly so that the transient key is set.
		maybeResetTransientKey();
		// Fill the DH FIFO on-thread
		for(int i=0;i<DH_CONTEXT_BUFFER_SIZE;i++) {
			_fillJFKDHFIFO();
		}
		if(!NodeStarter.bcProvLoadFailed()) {
			for(int i=0;i<DH_CONTEXT_BUFFER_SIZE;i++) {
				_fillJFKECDHFIFO();
			}
		}
	}

	/**
	 * Packet format:
	 *
	 * E_session_ecb(
	 *         4 bytes:  sequence number XOR first 4 bytes of node identity
	 *         12 bytes: first 12 bytes of H(data)
	 *         )
	 * E_session_ecb(
	 *         16 bytes: bytes 12-28 of H(data)
	 *         ) XOR previous ciphertext XOR previous plaintext
	 * 4 bytes: bytes 28-32 of H(data) XOR bytes 0-4 of H(data)
	 * E_session_pcfb(data) // IV = first 32 bytes of packet
	 *
	 */

	public DECODED process(byte[] buf, int offset, int length, PluginAddress address, long now) {
		/**
		 * Look up the Peer.
		 * If we know it, check the packet with that key.
		 * Otherwise try all of them (on the theory that nodes
		 * occasionally change their IP addresses).
		 */
		PeerNode opn = node.peers.getByAddress(address, sock);
		return process(buf, offset, length, address, opn, now);
	}

	/**
	 * Decrypt and authenticate packet.
	 * Then feed it to USM.checkFilters.
	 * Packets generated should have a PeerNode on them.
	 * Note that the buffer can be modified by this method.
	 */
	public DECODED process(byte[] buf, int offset, int length, PluginAddress address, PeerNode opn, long now) {
		PeerPacketTransport peerTransport = null;
		if(opn != null)
			peerTransport = opn.getPeerTransport(sock);

		if(peerTransport != null && peerTransport.outgoingMangler != this) {
			Logger.error(this, "Apparently contacted by "+peerTransport+") on "+this, new Exception("error"));
			opn = null;
		}
		PeerNode pn;
		boolean wantAnonAuth = crypto.wantAnonAuth();

		if(opn != null) {
			if(logMINOR) Logger.minor(this, "Trying exact match");
			if(length > Node.SYMMETRIC_KEY_LENGTH /* iv */ + HASH_LENGTH + 2 && !node.isStopping()) {
				// Might be an auth packet
				if(tryProcessAuth(buf, offset, length, peerTransport, address, false, now)) {
					return DECODED.DECODED;
				}
				// Might be a reply to us sending an anon auth packet.
				// I.e. we are not the seednode, they are.
				if(tryProcessAuthAnonReply(buf, offset, length, peerTransport, address, now)) {
					return DECODED.DECODED;
				}
			}
		}
		PeerNode[] peers = crypto.getPeerNodes();
		if(node.isStopping()) return DECODED.SHUTTING_DOWN;
		// Disconnected node connecting on a new IP address?
		if(length > Node.SYMMETRIC_KEY_LENGTH /* iv */ + HASH_LENGTH + 2) {
			for(int i=0;i<peers.length;i++) {
				pn = peers[i];
				PeerPacketTransport peerTransportPn = pn.getPeerTransport(sock);
				if(pn == opn) continue;
				if(logDEBUG)
					Logger.debug(this, "Trying auth with "+peerTransportPn);
				if(tryProcessAuth(buf, offset, length, peerTransportPn, address,false, now)) {
					return DECODED.DECODED;
				}
				if(pn.handshakeUnknownInitiator()) {
					// Might be a reply to us sending an anon auth packet.
					// I.e. we are not the seednode, they are.
					if(tryProcessAuthAnonReply(buf, offset, length, peerTransportPn, address, now)) {
						return DECODED.DECODED;
					}
				}
			}
		}
		
		boolean wantAnonAuthChangeIP = wantAnonAuth && crypto.wantAnonAuthChangeIP();
		
		if(wantAnonAuth && wantAnonAuthChangeIP) {
			if(checkAnonAuthChangeIP(peerTransport, buf, offset, length, address, now)) return DECODED.DECODED;
		}

		boolean didntTryOldOpennetPeers;
		OpennetManager opennet = node.getOpennet();
		if(opennet != null) {
			// Try old opennet connections.
			if(opennet.wantPeer(null, false, true, true, ConnectionType.RECONNECT)) {
				// We want a peer.
				// Try old connections.
				PeerNode[] oldPeers = opennet.getOldPeers();
				for(PeerNode oldPeer : oldPeers) {
					if(tryProcessAuth(buf, offset, length, oldPeer.getPeerTransport(sock), address, true, now)) return DECODED.DECODED;
				}
				didntTryOldOpennetPeers = false;
			} else
				didntTryOldOpennetPeers = true;
		} else
			didntTryOldOpennetPeers = false;
		if(wantAnonAuth) {
			if(tryProcessAuthAnon(buf, offset, length, address))
				return DECODED.DECODED;
		}
		
		if(wantAnonAuth && !wantAnonAuthChangeIP) {
			if(checkAnonAuthChangeIP(peerTransport, buf, offset, length, address, now)) {
				// This can happen when a node is upgraded from a SeedClientPeerNode to an OpennetPeerNode.
				//Logger.error(this, "Last resort match anon-auth against all anon setup peernodes succeeded - this should not happen! (It can happen if they change address)");
				return DECODED.DECODED;
			}
		}

                // Don't log too much if we are a seednode
                if(logMINOR && crypto.isOpennet && wantAnonAuth) {
                	if(!didntTryOldOpennetPeers)
                		Logger.minor(this,"Unmatchable packet from "+address);
                } else
                    Logger.normal(this,"Unmatchable packet from "+address);
                
                if(!didntTryOldOpennetPeers)
                	return DECODED.NOT_DECODED;
                else
                	return DECODED.DIDNT_WANT_OPENNET;
	}
	
	private boolean checkAnonAuthChangeIP(PeerPacketTransport peerTransport, byte[] buf, int offset, int length, PluginAddress address, long now) {
		PeerNode opn = peerTransport.pn;
		PeerNode[] anonPeers = crypto.getAnonSetupPeerNodes(sock);
		PeerNode pn;
		PeerPacketTransport peerTransportPn;
		if(length > Node.SYMMETRIC_KEY_LENGTH /* iv */ + HASH_LENGTH + 3) {
			for(int i=0;i<anonPeers.length;i++) {
				pn = anonPeers[i];
				peerTransportPn = pn.getPeerTransport(sock);
				if(pn == opn) continue;
				if(tryProcessAuthAnonReply(buf, offset, length, peerTransportPn, address, now)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Is this a negotiation packet? If so, process it.
	 * @param buf The buffer to read bytes from
	 * @param offset The offset at which to start reading
	 * @param length The number of bytes to read
	 * @param pn The PeerNode we think is responsible
	 * @param peer The Peer to send a reply to
	 * @param now The time at which the packet was received
	 * @return True if we handled a negotiation packet, false otherwise.
	 */
	private boolean tryProcessAuth(byte[] buf, int offset, int length, PeerPacketTransport peerTransport, PluginAddress address, boolean oldOpennetPeer, long now) {
		PeerNode pn = peerTransport.pn;
		BlockCipher authKey = pn.incomingSetupCipher;
		if(logDEBUG) Logger.debug(this, "Decrypt key: "+HexUtil.bytesToHex(pn.incomingSetupKey)+" for "+address+" : "+pn+" in tryProcessAuth");
		// Does the packet match IV E( H(data) data ) ?
		int ivLength = PCFBMode.lengthIV(authKey);
		int digestLength = HASH_LENGTH;
		if(length < digestLength + ivLength + 4) {
			if(logMINOR) {
				if(buf.length < length) {
					if(logDEBUG) Logger.debug(this, "The packet is smaller than the decrypted size: it's probably the wrong tracker ("+buf.length+'<'+length+')');
				} else {
					Logger.minor(this, "Too short: "+length+" should be at least "+(digestLength + ivLength + 4));
				}
			}
			return false;
		}
		// IV at the beginning
		PCFBMode pcfb = PCFBMode.create(authKey, buf, offset);
		// Then the hash, then the data
		// => Data starts at ivLength + digestLength
		// Decrypt the hash
		byte[] hash = Arrays.copyOfRange(buf, offset+ivLength, offset+ivLength+digestLength);
		pcfb.blockDecipher(hash, 0, hash.length);

		int dataStart = ivLength + digestLength + offset+2;

		int byte1 = ((pcfb.decipher(buf[dataStart-2])) & 0xff);
		int byte2 = ((pcfb.decipher(buf[dataStart-1])) & 0xff);
		int dataLength = (byte1 << 8) + byte2;
		if(logDEBUG) Logger.debug(this, "Data length: "+dataLength+" (1 = "+byte1+" 2 = "+byte2+ ')');
		if(dataLength > length - (ivLength+hash.length+2)) {
			if(logDEBUG) Logger.debug(this, "Invalid data length "+dataLength+" ("+(length - (ivLength+hash.length+2))+") in tryProcessAuth");
			return false;
		}
		// Decrypt the data
		byte[] payload = Arrays.copyOfRange(buf, dataStart, dataStart+dataLength);
		pcfb.blockDecipher(payload, 0, payload.length);

		byte[] realHash = SHA256.digest(payload);

		if(Arrays.equals(realHash, hash)) {
			// Got one
			processDecryptedAuth(payload, peerTransport, address, oldOpennetPeer);
			peerTransport.reportIncomingBytes(length);
			return true;
		} else {
			if(logDEBUG) Logger.debug(this, "Incorrect hash in tryProcessAuth for "+address+" (length="+dataLength+"): \nreal hash="+HexUtil.bytesToHex(realHash)+"\n bad hash="+HexUtil.bytesToHex(hash));
			return false;
		}
	}

	/**
	 * Might be an anonymous-initiator negotiation packet (i.e.
	 * we are the responder).
	 * Anonymous initiator is used for seednode connections,
	 * and will in future be used for other things for example
	 * one-side-only invites, password based invites etc.
	 * @param buf The buffer to read bytes from
	 * @param offset The offset at which to start reading
	 * @param length The number of bytes to read
	 * @param address The Peer to send a reply to
	 * @return True if we handled a negotiation packet, false otherwise.
	 */
	private boolean tryProcessAuthAnon(byte[] buf, int offset, int length, PluginAddress address) {
		BlockCipher authKey = crypto.getAnonSetupCipher();
		// Does the packet match IV E( H(data) data ) ?
		int ivLength = PCFBMode.lengthIV(authKey);
		int digestLength = HASH_LENGTH;
		if(length < digestLength + ivLength + 5) {
			if(logMINOR) Logger.minor(this, "Too short: "+length+" should be at least "+(digestLength + ivLength + 5));
			return false;
		}
		// IV at the beginning
		PCFBMode pcfb = PCFBMode.create(authKey, buf, offset);
		// Then the hash, then the data
		// => Data starts at ivLength + digestLength
		// Decrypt the hash
		byte[] hash = Arrays.copyOfRange(buf, offset+ivLength, offset+ivLength+digestLength);
		pcfb.blockDecipher(hash, 0, hash.length);

		int dataStart = ivLength + digestLength + offset+2;

		int byte1 = ((pcfb.decipher(buf[dataStart-2])) & 0xff);
		int byte2 = ((pcfb.decipher(buf[dataStart-1])) & 0xff);
		int dataLength = (byte1 << 8) + byte2;
		if(logMINOR) Logger.minor(this, "Data length: "+dataLength+" (1 = "+byte1+" 2 = "+byte2+ ')');
		if(dataLength > length - (ivLength+hash.length+2)) {
			if(logMINOR) Logger.minor(this, "Invalid data length "+dataLength+" ("+(length - (ivLength+hash.length+2))+") in tryProcessAuthAnon");
			return false;
		}
		// Decrypt the data
		byte[] payload = Arrays.copyOfRange(buf, dataStart, dataStart+dataLength);
		pcfb.blockDecipher(payload, 0, payload.length);

		byte[] realHash = SHA256.digest(payload);

		if(Arrays.equals(realHash, hash)) {
			// Got one
			processDecryptedAuthAnon(payload, address);
			return true;
		} else {
			if(logMINOR) Logger.minor(this, "Incorrect hash in tryProcessAuthAnon for "+address+" (length="+dataLength+"): \nreal hash="+HexUtil.bytesToHex(realHash)+"\n bad hash="+HexUtil.bytesToHex(hash));
			return false;
		}
	}

	/**
	 * Might be a reply to an anonymous-initiator negotiation
	 * packet (i.e. we are the initiator).
	 * Anonymous initiator is used for seednode connections,
	 * and will in future be used for other things for example
	 * one-side-only invites, password based invites etc.
	 * @param buf The buffer to read bytes from
	 * @param offset The offset at which to start reading
	 * @param length The number of bytes to read
	 * @param pn The PeerNode we think is responsible
	 * @param address The address to send a reply to
	 * @param now The time at which the packet was received
	 * @return True if we handled a negotiation packet, false otherwise.
	 */
	private boolean tryProcessAuthAnonReply(byte[] buf, int offset, int length, PeerPacketTransport peerTransport, PluginAddress address, long now) {
		PeerNode pn = peerTransport.pn;
		BlockCipher authKey = pn.anonymousInitiatorSetupCipher;
		// Does the packet match IV E( H(data) data ) ?
		int ivLength = PCFBMode.lengthIV(authKey);
		int digestLength = HASH_LENGTH;
		if(length < digestLength + ivLength + 5) {
			if(logDEBUG) Logger.debug(this, "Too short: "+length+" should be at least "+(digestLength + ivLength + 5));
			return false;
		}
		// IV at the beginning
		PCFBMode pcfb = PCFBMode.create(authKey, buf, offset);
		// Then the hash, then the data
		// => Data starts at ivLength + digestLength
		// Decrypt the hash
		byte[] hash = Arrays.copyOfRange(buf, offset+ivLength, offset+ivLength+digestLength);
		pcfb.blockDecipher(hash, 0, hash.length);

		int dataStart = ivLength + digestLength + offset+2;

		int byte1 = ((pcfb.decipher(buf[dataStart-2])) & 0xff);
		int byte2 = ((pcfb.decipher(buf[dataStart-1])) & 0xff);
		int dataLength = (byte1 << 8) + byte2;
		if(logDEBUG) Logger.minor(this, "Data length: "+dataLength+" (1 = "+byte1+" 2 = "+byte2+ ')');
		if(dataLength > length - (ivLength+hash.length+2)) {
			if(logDEBUG) Logger.debug(this, "Invalid data length "+dataLength+" ("+(length - (ivLength+hash.length+2))+") in tryProcessAuth");
			return false;
		}
		// Decrypt the data
		byte[] payload = Arrays.copyOfRange(buf, dataStart, dataStart+dataLength);
		pcfb.blockDecipher(payload, 0, payload.length);

		byte[] realHash = SHA256.digest(payload);

		if(Arrays.equals(realHash, hash)) {
			// Got one
			processDecryptedAuthAnonReply(payload, address, peerTransport);
			return true;
		} else {
			if(logDEBUG) Logger.debug(this, "Incorrect hash in tryProcessAuth for "+address+" (length="+dataLength+"): \nreal hash="+HexUtil.bytesToHex(realHash)+"\n bad hash="+HexUtil.bytesToHex(hash));
			return false;
		}
	}

	// Anonymous-initiator setup types
	/** Connect to a node hoping it will act as a seednode for us */
	static final byte SETUP_OPENNET_SEEDNODE = 1;

	/**
	 * Process an anonymous-initiator connection setup packet. For a normal setup
	 * (@see processDecryptedAuth()), we know the node that is trying to contact us.
	 * But in this case, we don't know the node yet, and we are doing a
	 * special-purpose connection setup. At the moment the only type supported is
	 * for a new node connecting to a seednode in order to announce. In future,
	 * nodes may support other anonymous-initiator connection types such as when a
	 * node (which is certain of its connectivity) issues one-time invites which
	 * allow a new node to connect to it.
	 * @param payload The decrypted payload of the packet.
	 * @param replyTo The address the packet came in from.
	 */
	private void processDecryptedAuthAnon(byte[] payload, PluginAddress replyTo) {
		if(logMINOR) Logger.minor(this, "Processing decrypted auth packet from "+replyTo+" length "+payload.length);

		/** Protocol version. Should be 1. */
		int version = payload[0];
		/** Negotiation type. Common to anonymous-initiator auth and normal setup.
		 *   2 = JFK.
		 *   3 = JFK, reuse PacketTracker
		 * Other types might indicate other DH variants, or even non-DH-based
		 * algorithms such as password based key setup. */
		int negType = payload[1];
		/** Packet phase. */
		int packetType = payload[2];
		/** Setup type. This is specific to anonymous-initiator setup, and specifies the
		 * purpose of the connection. At the moment it is SETUP_OPENNET_SEEDNODE to indicate
		 * we are connecting to a seednode (which doesn't know us). Invites might require
		 * a different setupType. */
		int setupType = payload[3];

		if(logMINOR) Logger.minor(this, "Received anonymous auth packet (phase="+packetType+", v="+version+", nt="+negType+", setup type="+setupType+") from "+replyTo+"");

		if(version != 1) {
			Logger.error(this, "Decrypted auth packet but invalid version: "+version);
			return;
		}
		if(!(negType == 6 || negType == 7 || negType == 8)) {
			if(negType > 8)
				Logger.error(this, "Unknown neg type: "+negType);
			else
				Logger.warning(this, "Received a setup packet with unsupported obsolete neg type: "+negType);
			return;
		}

		// Known setup types
		if(setupType != SETUP_OPENNET_SEEDNODE) {
			Logger.error(this, "Unknown setup type "+negType);
			return;
		}

		// We are the RESPONDER.
		// Therefore, we can only get packets of phase 1 and 3 here.

		if(packetType == 0) {
			// Phase 1
			processJFKMessage1(payload,4,null,replyTo, true, setupType, negType);
		} else if(packetType == 2) {
			// Phase 3
			processJFKMessage3(payload, 4, null, replyTo, false, true, setupType, negType);
		} else {
			Logger.error(this, "Invalid phase "+packetType+" for anonymous-initiator (we are the responder) from "+replyTo);
		}
	}

	private void processDecryptedAuthAnonReply(byte[] payload, PluginAddress replyTo, PeerPacketTransport peerTransport) {
		if(logMINOR) Logger.minor(this, "Processing decrypted auth packet from "+replyTo+" for "+peerTransport+" length "+payload.length);

		/** Protocol version. Should be 1. */
		int version = payload[0];
		/** Negotiation type.
		 *   2 = JFK.
		 *   3 = JFK, reuse PacketTracker
		 * Other types might indicate other DH variants, or even non-DH-based
		 * algorithms such as password based key setup. */
		int negType = payload[1];
		/** Packet phase. */
		int packetType = payload[2];
		/** Setup type. See above. */
		int setupType = payload[3];

		if(logMINOR) Logger.minor(this, "Received anonymous auth packet (phase="+packetType+", v="+version+", nt="+negType+", setup type="+setupType+") from "+replyTo+"");

		if(version != 1) {
			Logger.error(this, "Decrypted auth packet but invalid version: "+version);
			return;
		}
		if(!(negType == 6 || negType == 7 || negType == 8)) {
			if(negType > 8)
				Logger.error(this, "Unknown neg type: "+negType);
			else
				Logger.warning(this, "Received a setup packet with unsupported obsolete neg type: "+negType);
			return;
		}

		// Known setup types
		if(setupType != SETUP_OPENNET_SEEDNODE) {
			Logger.error(this, "Unknown setup type "+negType);
			return;
		}

		// We are the INITIATOR.
		// Therefore, we can only get packets of phase 2 and 4 here.

		if(packetType == 1) {
			// Phase 2
			processJFKMessage2(payload, 4, peerTransport, replyTo, true, setupType, negType);
		} else if(packetType == 3) {
			// Phase 4
			processJFKMessage4(payload, 4, peerTransport, replyTo, false, true, setupType, negType);
		} else {
			Logger.error(this, "Invalid phase "+packetType+" for anonymous-initiator (we are the initiator) from "+replyTo);
		}
	}

	/**
	 * Process a decrypted, authenticated auth packet.
	 * @param payload The packet payload, after it has been decrypted.
	 */
	private void processDecryptedAuth(byte[] payload, PeerPacketTransport peerTransport, PluginAddress replyTo, boolean oldOpennetPeer) {
		PeerNode pn = peerTransport.pn;
		if(logMINOR) Logger.minor(this, "Processing decrypted auth packet from "+replyTo+" for "+peerTransport);
		if(pn.isDisabled()) {
			if(logMINOR) Logger.minor(this, "Won't connect to a disabled peer ("+pn+ ')');
			return;  // We don't connect to disabled peers
		}

		int negType = payload[1];
		int packetType = payload[2];
		int version = payload[0];

		if(logMINOR) {
			long now = System.currentTimeMillis();
			long last = peerTransport.lastSentTransportPacketTime();
			String delta = "never";
			if (last>0) {
				delta = TimeUtil.formatTime(now-last, 2, true)+" ago";
			}
			Logger.minor(this, "Received auth packet for "+peerTransport.getAddress()+" (phase="+packetType+", v="+version+", nt="+negType+") (last packet sent "+delta+") from "+replyTo+"");
		}

		/* Format:
		 * 1 byte - version number (1)
		 * 1 byte - negotiation type (0 = simple DH, will not be supported when implement JFKi || 1 = StS)
		 * 1 byte - packet type (0-3)
		 */
		if(version != 1) {
			Logger.error(this, "Decrypted auth packet but invalid version: "+version);
			return;
		}

		if(negType >= 0 && negType < 6) {
			// negType 0 through 5 no longer supported, used old FNP.
			Logger.warning(this, "Old neg type "+negType+" not supported");
			return;
		} else if (negType == 6 || negType == 7 || negType == 8) {
		    // negType == 8 => use ECDH with secp256r1 instead of DH
			// negType == 7 => same as 6, but determine the initial sequence number by hashing the identity
			// instead of negotiating it
			/*
			 * We implement Just Fast Keying key management protocol with active identity protection
			 * for the initiator and no identity protection for the responder
			 * M1:
			 * This is a straightforward DiffieHellman exponential.
			 * The Initiator Nonce serves two purposes;it allows the initiator to use the same
			 * exponentials during different sessions while ensuring that the resulting session
			 * key will be different,can be used to differentiate between parallel sessions
			 * M2:
			 * Responder replies with a signed copy of his own exponential, a random nonce and
			 * an authenticator which provides sufficient defense against forgeries,replays
			 * We slightly deviate JFK here;we do not send any public key information as specified in the JFK docs
			 * M3:
			 * Initiator echoes the data sent by the responder including the authenticator.
			 * This helps the responder verify the authenticity of the returned data.
			 * M4:
			 * Encrypted message of the signature on both nonces, both exponentials using the same keys as in the previous message
			 */
			if(packetType<0 || packetType>3) {
				Logger.error(this,"Unknown PacketType" + packetType + "from" + replyTo + "from" +peerTransport);
				return ;
			} else if(packetType==0) {
				/*
				 * Initiator- This is a straightforward DiffieHellman exponential.
				 * The Initiator Nonce serves two purposes;it allows the initiator to use the same
				 * exponentials during different sessions while ensuring that the resulting
				 * session key will be different,can be used to differentiate between
				 * parallel sessions
				 */
				processJFKMessage1(payload,3,peerTransport,replyTo,false,-1,negType);

			} else if(packetType==1) {
				/*
				 * Responder replies with a signed copy of his own exponential, a random
				 * nonce and an authenticator calculated from a transient hash key private
				 * to the responder.
				 */
				processJFKMessage2(payload,3,peerTransport,replyTo,false,-1,negType);
			} else if(packetType==2) {
				/*
				 * Initiator echoes the data sent by the responder.These messages are
				 * cached by the Responder.Receiving a duplicate message simply causes
				 * the responder to Re-transmit the corresponding message4
				 */
				processJFKMessage3(payload, 3, peerTransport, replyTo, oldOpennetPeer, false, -1, negType);
			} else if(packetType==3) {
				/*
				 * Encrypted message of the signature on both nonces, both exponentials
				 * using the same keys as in the previous message.
				 * The signature is non-message recovering
				 */
				processJFKMessage4(payload, 3, peerTransport, replyTo, oldOpennetPeer, false, -1, negType);
			}
		} else {
			Logger.error(this, "Decrypted auth packet but unknown negotiation type "+negType+" from "+replyTo+" possibly from "+pn);
			return;
		}
	}

	/*
	 * Initiator Method:Message1
	 * Process Message1
	 * Receive the Initiator nonce and DiffieHellman Exponential
	 * @param The packet phase number
	 * @param The peerNode we are talking to. CAN BE NULL if anonymous initiator, since we are the responder.
	 * @param The peer to which we need to send the packet
	 * @param unknownInitiator If true, we (the responder) don't know the
	 * initiator, and should check for fields which would be skipped in a
	 * normal setup where both sides know the other (indicated with * below).
	 * @param setupType The type of unknown-initiator setup.
	 *
	 * format :
	 * Ni
	 * g^i
	 * *IDr'
	 *
	 * See http://www.wisdom.weizmann.ac.il/~reingold/publications/jfk-tissec.pdf
	 * Just Fast Keying: Key Agreement In A Hostile Internet
	 * Aiello, Bellovin, Blaze, Canetti, Ioannidis, Keromytis, Reingold.
	 * ACM Transactions on Information and System Security, Vol 7 No 2, May 2004, Pages 1-30.
	 *
	 */
	private void processJFKMessage1(byte[] payload, int offset, PeerPacketTransport peerTransport, PluginAddress replyTo, boolean unknownInitiator, int setupType, int negType) {
		long t1=System.currentTimeMillis();
        int modulusLength = getModulusLength(negType);
		if(logMINOR) Logger.minor(this, "Got a JFK(1) message, processing it - "+peerTransport);
		// FIXME: follow the spec and send IDr' ?
		if(payload.length < NONCE_SIZE + modulusLength + 3 + (unknownInitiator ? NodeCrypto.IDENTITY_LENGTH : 0)) {
			Logger.error(this, "Packet too short from "+peerTransport+": "+payload.length+" after decryption in JFK(1), should be "+(NONCE_SIZE + modulusLength));
			return;
		}
		// get Ni
		byte[] nonceInitiator = Arrays.copyOfRange(payload, offset, offset + NONCE_SIZE);
		offset += NONCE_SIZE;

		// get g^i
		byte[] hisExponential = Arrays.copyOfRange(payload, offset, offset + modulusLength);
		if(unknownInitiator) {
			// Check IDr'
			offset += modulusLength;
			byte[] expectedIdentityHash = Arrays.copyOfRange(payload, offset, offset + NodeCrypto.IDENTITY_LENGTH);
			if(!Arrays.equals(expectedIdentityHash, crypto.identityHash)) {
				Logger.error(this, "Invalid unknown-initiator JFK(1), IDr' is "+HexUtil.bytesToHex(expectedIdentityHash)+" should be "+HexUtil.bytesToHex(crypto.identityHash));
				return;
			}
		}
		
		if(throttleRekey(peerTransport, replyTo)) return;

		if(negType >= 8 || DiffieHellman.checkDHExponentialValidity(this.getClass(), new NativeBigInteger(1,hisExponential))) {
			// JFK protects us from weak key attacks on ECDH, so we don't need to check.
		    try {
		    	sendJFKMessage2(nonceInitiator, hisExponential, peerTransport, replyTo, unknownInitiator, setupType, negType);
		    } catch (NoContextsException e) {
		    	handleNoContextsException(e, NoContextsException.CONTEXT.REPLYING);
		    	return;
		    }
		} else {
			Logger.error(this, "We can't accept the exponential "+peerTransport+" sent us!! REDFLAG: IT CAN'T HAPPEN UNLESS AGAINST AN ACTIVE ATTACKER!!");
		}

		long t2=System.currentTimeMillis();
		if((t2-t1)>500) {
			Logger.error(this,"Message1 timeout error:Processing packet for "+peerTransport);
		}
	}
	
	private long lastLoggedNoContexts = -1;
	private static int LOG_NO_CONTEXTS_INTERVAL = 60*1000;
	
	private void handleNoContextsException(NoContextsException e,
			freenet.node.FNPPacketMangler.NoContextsException.CONTEXT context) {
		if(node.getUptime() < 30*1000) {
			Logger.warning(this, "No contexts available, unable to handle or send packet ("+context+") on "+this);
			return;
		}
		// Log it immediately.
		Logger.warning(this, "No contexts available "+context+" - running out of entropy or severe CPU usage problems?");
		// More loudly periodically.
		long now = System.currentTimeMillis();
		synchronized(this) {
			if(now < lastLoggedNoContexts + LOG_NO_CONTEXTS_INTERVAL)
				return;
			lastLoggedNoContexts = now;
		}
		logLoudErrorNoContexts();
	}

	private void logLoudErrorNoContexts() {
		// If this is happening regularly post-startup then it's unlikely that reading the disk will help.
		// FIXME localise this, give a useralert etc.
		// RNG exhaustion shouldn't happen for Windows users at all, and may not happen on Linux depending on the JVM version, so lets leave it for now.
		System.err.println("FREENET IS HAVING PROBLEMS CONNECTING: Either your CPU is overloaded or it is having trouble reading from the random number generator");
		System.err.println("If the problem is CPU usage, please shut down whatever applications are hogging the CPU.");
		if(FileUtil.detectedOS.isUnix) {
			File f = new File("/dev/hwrng");
			if(f.exists())
				System.err.println("Installing \"rngd\" might help (e.g. apt-get install rng-tools).");
			System.err.println("The best solution is to install a hardware random number generator, or use turbid or similar software to take random data from an unconnected sound card.");
			System.err.println("The quick workaround is to add \"wrapper.java.additional.4=-Djava.security.egd=file:///dev/urandom\" to your wrapper.conf.");
		}
	}

	private final LRUMap<PluginAddress, Long> throttleRekeysByAddress = LRUMap.createSafeMap(PluginAddressComparator.COMPARATOR);

	private static final int REKEY_BY_IP_TABLE_SIZE = 1024;

	private boolean throttleRekey(PeerPacketTransport peerTransport, PluginAddress replyTo) {
		PluginAddress addr = replyTo;
		if(peerTransport != null) {
			return peerTransport.throttleRekey();
		}
		long now = System.currentTimeMillis();
		addr = addr.getPhysicalAddress();
		synchronized(throttleRekeysByAddress) {
			Long l = throttleRekeysByAddress.get(addr);
			if(l == null || l != null && now > l)
				throttleRekeysByAddress.push(addr, now);
			while(throttleRekeysByAddress.size() > REKEY_BY_IP_TABLE_SIZE || 
					((!throttleRekeysByAddress.isEmpty()) && throttleRekeysByAddress.peekValue() < now - PeerNode.THROTTLE_REKEY))
				throttleRekeysByAddress.popKey();
			if(l != null && now - l < PeerNode.THROTTLE_REKEY) {
				Logger.error(this, "Two JFK(1)'s initiated by same IP within "+PeerNode.THROTTLE_REKEY+"ms");
				return true;
			}
		}
		return false;
	}

	private static final int MAX_NONCES_PER_PEER = 10;

	/*
	 * format:
	 * Ni,g^i
	 * We send IDr' only if unknownInitiator is set.
	 * @param pn The node to encrypt the message to. Cannot be null, because we are the initiator and we
	 * know the responder in all cases.
	 * @param replyTo The peer to send the actual packet to.
	 */
	private void sendJFKMessage1(PeerPacketTransport peerTransport, PluginAddress replyTo, boolean unknownInitiator, int setupType, int negType) throws NoContextsException {
		if(logMINOR) Logger.minor(this, "Sending a JFK(1) message to "+replyTo+" for "+peerTransport.getAddress());
		final long now = System.currentTimeMillis();
		int modulusLength = getModulusLength(negType);
		
		
		KeyAgreementSchemeContext ctx = peerTransport.getKeyAgreementSchemeContext();
		if(negType < 8) { // Legacy DH
		    if((ctx == null) || !(ctx instanceof DiffieHellmanLightContext) || ((peerTransport.jfkContextLifetime + DH_GENERATION_INTERVAL*DH_CONTEXT_BUFFER_SIZE) < now)) {
			    peerTransport.jfkContextLifetime = now;
			    peerTransport.setKeyAgreementSchemeContext(ctx = getLightDiffieHellmanContext());
		    }
		} else {
            if((ctx == null) || !(ctx instanceof ECDHLightContext) || ((peerTransport.jfkContextLifetime + DH_GENERATION_INTERVAL*DH_CONTEXT_BUFFER_SIZE) < now)) {
                peerTransport.jfkContextLifetime = now;
                peerTransport.setKeyAgreementSchemeContext(ctx = getECDHLightContext());
            }
		}
		
		int offset = 0;
		byte[] nonce = new byte[NONCE_SIZE];
		byte[] myExponential = ctx.getPublicKeyNetworkFormat();
		node.random.nextBytes(nonce);

		synchronized (peerTransport) {
			peerTransport.jfkNoncesSent.add(nonce);
			if(peerTransport.jfkNoncesSent.size() > MAX_NONCES_PER_PEER)
				peerTransport.jfkNoncesSent.removeFirst();
		}

		byte[] message1 = new byte[NONCE_SIZE+modulusLength+(unknownInitiator ? NodeCrypto.IDENTITY_LENGTH : 0)];

		System.arraycopy(nonce, 0, message1, offset, NONCE_SIZE);
		offset += NONCE_SIZE;
		System.arraycopy(myExponential, 0, message1, offset, modulusLength);

		if(unknownInitiator) {
			offset += modulusLength;
			System.arraycopy(peerTransport.pn.identityHash, 0, message1, offset, peerTransport.pn.identityHash.length);
			sendAnonAuthPacket(1,negType,0,setupType,message1,peerTransport,replyTo,peerTransport.pn.anonymousInitiatorSetupCipher);
		} else {
			sendAuthPacket(1,negType,0,message1,peerTransport,replyTo);
		}
		long t2=System.currentTimeMillis();
		if((t2-now)>500) {
			Logger.error(this,"Message1 timeout error:Sending packet for "+peerTransport.getAddress());
		}
	}

	/*
	 * format:
	 * Ni,Nr,g^r
	 * Signature[g^r,grpInfo(r)] - R, S
	 * Hashed JFKAuthenticator : HMAC{Hkr}[g^r, g^i, Nr, Ni, IPi]
	 *
	 * NB: we don't send IDr nor groupinfo as we know them: even if the responder doesn't know the initiator,
	 * the initiator ALWAYS knows the responder.
	 * @param pn The node to encrypt the message for. CAN BE NULL if anonymous-initiator.
	 * @param replyTo The peer to send the packet to.
	 */
	private void sendJFKMessage2(byte[] nonceInitator, byte[] hisExponential, PeerPacketTransport peerTransport, PluginAddress replyTo, boolean unknownInitiator, int setupType, int negType) throws NoContextsException {
		if(logMINOR) Logger.minor(this, "Sending a JFK(2) message to "+peerTransport);
		int modulusLength = getModulusLength(negType);

		// g^r
		KeyAgreementSchemeContext ctx = (negType < 8 ? getLightDiffieHellmanContext() : getECDHLightContext());
	    DSASignature sig = ctx.signature;
		    
		// Nr
		byte[] myNonce = new byte[NONCE_SIZE];
		node.random.nextBytes(myNonce);
	    byte[] myExponential = ctx.getPublicKeyNetworkFormat();
		byte[] r = sig.getRBytes(Node.SIGNATURE_PARAMETER_LENGTH);
		byte[] s = sig.getSBytes(Node.SIGNATURE_PARAMETER_LENGTH);
		// replyTo.getPhysicalAddress().getBytes() is used.
		// Otherwise authentication might fail consistently in some transports. 
		byte[] authenticator = HMAC.macWithSHA256(getTransientKey(),assembleJFKAuthenticator(myExponential, hisExponential, myNonce, nonceInitator, replyTo.getPhysicalAddress().getBytes()), HASH_LENGTH);
		if(logMINOR) Logger.minor(this, "We are using the following HMAC : " + HexUtil.bytesToHex(authenticator));

		byte[] message2 = new byte[NONCE_SIZE*2+modulusLength+
		                           Node.SIGNATURE_PARAMETER_LENGTH*2+
		                           HASH_LENGTH];

		int offset = 0;
		System.arraycopy(nonceInitator, 0, message2, offset, NONCE_SIZE);
		offset += NONCE_SIZE;
		System.arraycopy(myNonce, 0, message2, offset, NONCE_SIZE);
		offset += NONCE_SIZE;
		System.arraycopy(myExponential, 0, message2, offset, modulusLength);
		offset += modulusLength;

		System.arraycopy(r, 0, message2, offset, Node.SIGNATURE_PARAMETER_LENGTH);
		offset += Node.SIGNATURE_PARAMETER_LENGTH;
		System.arraycopy(s, 0, message2, offset, Node.SIGNATURE_PARAMETER_LENGTH);
		offset += Node.SIGNATURE_PARAMETER_LENGTH;

		System.arraycopy(authenticator, 0, message2, offset, HASH_LENGTH);

		if(unknownInitiator) {
			sendAnonAuthPacket(1,negType,1,setupType,message2,peerTransport,replyTo,crypto.anonSetupCipher);
		} else {
			sendAuthPacket(1,negType,1,message2,peerTransport,replyTo);
		}
	}

	/*
	 * Assemble what will be the jfk-Authenticator :
	 * computed over the Responder exponentials and the Nonces and
	 * used by the responder to verify that the round-trip has been done
	 *
	 */
	private byte[] assembleJFKAuthenticator(byte[] gR, byte[] gI, byte[] nR, byte[] nI, byte[] address) {
		byte[] authData=new byte[gR.length + gI.length + nR.length + nI.length + address.length];
		int offset = 0;

		System.arraycopy(gR, 0, authData, offset ,gR.length);
		offset += gR.length;
		System.arraycopy(gI, 0, authData, offset, gI.length);
		offset += gI.length;
		System.arraycopy(nR, 0,authData, offset, nR.length);
		offset += nR.length;
		System.arraycopy(nI, 0,authData, offset, nI.length);
		offset += nI.length;
		System.arraycopy(address, 0, authData, offset, address.length);

		return authData;
	}

	/*
	 * Initiator Method:Message2
	 * @see{sendJFKMessage2} for packet format details.
	 * Note that this packet is exactly the same for known initiator as for unknown initiator.
	 *
	 * @param payload The buffer containing the decrypted auth packet.
	 * @param inputOffset The offset in the buffer at which the packet starts.
	 * @param replyTo The peer to which we need to send the packet
	 * @param pn The peerNode we are talking to. Cannot be null as we are the initiator.
	 */

	private void processJFKMessage2(byte[] payload, int inputOffset, PeerPacketTransport peerTransport, PluginAddress replyTo, boolean unknownInitiator, int setupType, int negType)
	{
		long t1=System.currentTimeMillis();
		int modulusLength = getModulusLength(negType);
		if(logMINOR) Logger.minor(this, "Got a JFK(2) message, processing it - "+peerTransport.getAddress());
		// FIXME: follow the spec and send IDr' ?
		int expectedLength = NONCE_SIZE*2 + modulusLength + HASH_LENGTH*2;
		if(payload.length < expectedLength + 3) {
			Logger.error(this, "Packet too short from "+peerTransport.getAddress()+": "+payload.length+" after decryption in JFK(2), should be "+(expectedLength + 3));
			return;
		}

		byte[] nonceInitiator = Arrays.copyOfRange(payload, inputOffset, inputOffset+NONCE_SIZE);
		inputOffset += NONCE_SIZE;
		byte[] nonceResponder = Arrays.copyOfRange(payload, inputOffset, inputOffset+NONCE_SIZE);
		inputOffset += NONCE_SIZE;

		byte[] hisExponential = Arrays.copyOfRange(payload, inputOffset, inputOffset+modulusLength);
		inputOffset += modulusLength;

		byte[] r = Arrays.copyOfRange(payload, inputOffset, inputOffset+Node.SIGNATURE_PARAMETER_LENGTH);
		inputOffset += Node.SIGNATURE_PARAMETER_LENGTH;
		byte[] s = Arrays.copyOfRange(payload, inputOffset, inputOffset+Node.SIGNATURE_PARAMETER_LENGTH);
		inputOffset += Node.SIGNATURE_PARAMETER_LENGTH;

		byte[] authenticator = Arrays.copyOfRange(payload, inputOffset, inputOffset + HASH_LENGTH);
		inputOffset += HASH_LENGTH;

		// Check try to find the authenticator in the cache.
		// If authenticator is already present, indicates duplicate/replayed message2
		// Now simply transmit the corresponding message3
		Object message3 = null;
		synchronized (authenticatorCache) {
			message3 = authenticatorCache.get(new ByteArrayWrapper(authenticator));
		}
		if(message3 != null) {
			Logger.normal(this, "We replayed a message from the cache (shouldn't happen often) - "+peerTransport.getAddress());
			sendAuthPacket(1, negType, 3, (byte[]) message3, peerTransport, replyTo);
			return;
		}

		// sanity check
		byte[] myNi = null;
		synchronized (peerTransport) {
			for(byte[] buf : peerTransport.jfkNoncesSent) {
				if(Arrays.equals(nonceInitiator, buf))
					myNi = buf;
			}
		}
		// We don't except such a message;
		if(myNi == null) {
			if(shouldLogErrorInHandshake(t1)) {
				Logger.normal(this, "We received an unexpected JFK(2) message from "+peerTransport.getAddress()+" (time since added: "+peerTransport.pn.timeSinceAddedOrRestarted()+" time last receive:"+peerTransport.lastReceivedTransportPacketTime()+')');
			}
			return;
		} else if(!Arrays.equals(myNi, nonceInitiator)) {
			if(shouldLogErrorInHandshake(t1)) {
				Logger.normal(this, "Ignoring old JFK(2) (different nonce to the one we sent - either a timing artefact or an attempt to change the nonce)");
			}
			return;
		}

		if(negType < 8) { // legacy DH
		    NativeBigInteger _hisExponential = new NativeBigInteger(1,hisExponential);
		    if(!DiffieHellman.checkDHExponentialValidity(this.getClass(), _hisExponential)) {
		        Logger.error(this, "We can't accept the exponential "+peerTransport.getAddress()+" sent us!! REDFLAG: IT CAN'T HAPPEN UNLESS AGAINST AN ACTIVE ATTACKER!!");
		        return;
		    }
			// JFK protects us from weak key attacks on ECDH, so we don't need to check.
		}

		// Verify the DSA signature
		DSASignature remoteSignature = new DSASignature(new NativeBigInteger(1,r), new NativeBigInteger(1,s));
		// At that point we don't know if it's "him"; let's check it out
		byte[] locallyExpectedExponentials = assembleDHParams(hisExponential, peerTransport.pn.peerCryptoGroup);

		if(!DSA.verify(peerTransport.pn.peerPubKey, remoteSignature, new NativeBigInteger(1, SHA256.digest(locallyExpectedExponentials)), false)) {
			Logger.error(this, "The signature verification has failed in JFK(2)!! "+peerTransport.getAddress());
			return;
		}

		// At this point we know it's from the peer, so we can report a packet received.
		peerTransport.receivedPacket(true, false);

		sendJFKMessage3(1, negType, 3, nonceInitiator, nonceResponder, hisExponential, authenticator, peerTransport, replyTo, unknownInitiator, setupType);

		long t2=System.currentTimeMillis();
		if((t2-t1)>500) {
			Logger.error(this,"Message2 timeout error:Processing packet for "+peerTransport.getAddress());
		}
	}

	/*
	 * Initiator Method:Message3
	 * Process Message3
	 * Send the Initiator nonce,Responder nonce and DiffieHellman Exponential of the responder
	 * and initiator in the clear.(unVerifiedData)
	 * Send the authenticator which allows the responder to verify that a roundtrip occured
	 * Compute the signature of the unVerifiedData and encrypt it using a shared key
	 * which is derived from DHExponentials and the nonces; add a HMAC to protect it
	 *
	 * Format:
	 * Ni, Nr, g^i, g^r
	 * Authenticator - HMAC{Hkr}[g^r, g^i, Nr, Ni, IPi]
	 * HMAC{Ka}(cyphertext)
	 * IV + E{KE}[S{i}[Ni,Nr,g^i,g^r,idR, bootID, znoderefI], bootID, znoderefI*]
	 *
	 * * Noderef is sent whether or not unknownInitiator is true, however if it is, it will
	 * be a *full* noderef, otherwise it will exclude the pubkey etc.
	 *
	 * @param payload The buffer containing the decrypted auth packet.
	 * @param replyTo The peer to which we need to send the packet.
	 * @param pn The PeerNode we are talking to. CAN BE NULL in the case of anonymous initiator since we are the
	 * responder.
	 * @return byte Message3
	 */
	private void processJFKMessage3(byte[] payload, int inputOffset, PeerPacketTransport peerTransport, PluginAddress replyTo, boolean oldOpennetPeer, boolean unknownInitiator, int setupType, int negType)
	{
		PeerNode pn = peerTransport.pn;
		final long t1 = System.currentTimeMillis();
		int modulusLength = getModulusLength(negType);
		if(logMINOR) Logger.minor(this, "Got a JFK(3) message, processing it - "+peerTransport);

		BlockCipher c = null;
		try { c = new Rijndael(256, 256); } catch (UnsupportedCipherException e) { throw new RuntimeException(e); }

		final int expectedLength =
			NONCE_SIZE*2 + // Ni, Nr
			modulusLength*2 + // g^i, g^r
			HASH_LENGTH + // authenticator
			HASH_LENGTH + // HMAC of the cyphertext
			(c.getBlockSize() >> 3) + // IV
			HASH_LENGTH + // it's at least a signature
			8 +	      // a bootid
			8 +		  // packet tracker ID 
			1;	      // znoderefI* is at least 1 byte long

		if(payload.length < expectedLength + 3) {
			Logger.error(this, "Packet too short from "+peerTransport+": "+payload.length+" after decryption in JFK(3), should be "+(expectedLength + 3));
			return;
		}

		// Ni
		byte[] nonceInitiator = Arrays.copyOfRange(payload, inputOffset, inputOffset+NONCE_SIZE);
		inputOffset += NONCE_SIZE;
		// Nr
		byte[] nonceResponder = Arrays.copyOfRange(payload, inputOffset, inputOffset+NONCE_SIZE);
		inputOffset += NONCE_SIZE;
		// g^i
		byte[] initiatorExponential = Arrays.copyOfRange(payload, inputOffset, inputOffset+modulusLength);
		inputOffset += modulusLength;
		// g^r
		byte[] responderExponential = Arrays.copyOfRange(payload, inputOffset, inputOffset+modulusLength);
		inputOffset += modulusLength;

		byte[] authenticator = Arrays.copyOfRange(payload, inputOffset, inputOffset+HASH_LENGTH);
		inputOffset += HASH_LENGTH;

		// We *WANT* to check the hmac before we do the lookup on the hashmap
		// @see https://bugs.freenetproject.org/view.php?id=1604
		// replyTo.getPhysicalAddress().getBytes() is used.
		// Otherwise authentication might fail consistently in some transports. 
		if(!HMAC.verifyWithSHA256(getTransientKey(), assembleJFKAuthenticator(responderExponential, initiatorExponential, nonceResponder, nonceInitiator, replyTo.getPhysicalAddress().getBytes()) , authenticator)) {
			if(shouldLogErrorInHandshake(t1)) {
				Logger.normal(this, "The HMAC doesn't match; let's discard the packet (either we rekeyed or we are victim of forgery) - JFK3 - "+peerTransport);
			}
			return;
		}
		// Check try to find the authenticator in the cache.
		// If authenticator is already present, indicates duplicate/replayed message3
		// Now simply transmit the corresponding message4
		Object message4 = null;
		synchronized (authenticatorCache) {
			message4 = authenticatorCache.get(new ByteArrayWrapper(authenticator));
		}
		if(message4 != null) {
			Logger.normal(this, "We replayed a message from the cache (shouldn't happen often) - "+peerTransport);
			// We are replaying a JFK(4).
			// Therefore if it is anon-initiator it is encrypted with our setup key.
			if(unknownInitiator) {
				sendAnonAuthPacket(1,negType,3,setupType, (byte[]) message4, null, replyTo, crypto.anonSetupCipher);
			} else {
				sendAuthPacket(1, negType, 3, (byte[]) message4, peerTransport, replyTo);
			}
			return;
		} else {
			if(logDEBUG) Logger.debug(this, "No message4 found for "+HexUtil.bytesToHex(authenticator)+" responderExponential "+Fields.hashCode(responderExponential)+" initiatorExponential "+Fields.hashCode(initiatorExponential)+" nonceResponder "+Fields.hashCode(nonceResponder)+" nonceInitiator "+Fields.hashCode(nonceInitiator)+" address "+HexUtil.bytesToHex(replyTo.getBytes()));
		}

		byte[] hmac = Arrays.copyOfRange(payload, inputOffset, inputOffset+HASH_LENGTH);
		inputOffset += HASH_LENGTH;

		byte[] computedExponential;
		if(negType < 8) { // Legacy DH
			NativeBigInteger _hisExponential = new NativeBigInteger(1, initiatorExponential);
			NativeBigInteger _ourExponential = new NativeBigInteger(1, responderExponential);

			DiffieHellmanLightContext ctx = findContextByExponential(_ourExponential);
			if(ctx == null) {
				Logger.error(this, "WTF? the HMAC verified but we don't know about that exponential! SHOULDN'T HAPPEN! - JFK3 - "+peerTransport);
				// Possible this is a replay or severely delayed? We don't keep every exponential we ever use.
				return;
			}
			computedExponential = ctx.getHMACKey(_hisExponential);
        } else {
            ECPublicKey initiatorKey = ECDH.getPublicKey(initiatorExponential);
            ECPublicKey responderKey = ECDH.getPublicKey(responderExponential);
            ECDHLightContext ctx = findECDHContextByPubKey(responderKey);
            if (ctx == null) {
                Logger.error(this, "WTF? the HMAC verified but we don't know about that exponential! SHOULDN'T HAPPEN! - JFK3 - "+peerTransport);
                // Possible this is a replay or severely delayed? We don't keep
                // every exponential we ever use.
                return;
            }
            computedExponential = ctx.getHMACKey(initiatorKey).getEncoded();
        }
		if(logDEBUG) Logger.debug(this, "The shared Master secret is : "+HexUtil.bytesToHex(computedExponential) +" for " + peerTransport);
		
		/* 0 is the outgoing key for the initiator, 7 for the responder */
		byte[] outgoingKey = computeJFKSharedKey(computedExponential, nonceInitiator, nonceResponder, "7");
		byte[] incommingKey = computeJFKSharedKey(computedExponential, nonceInitiator, nonceResponder, "0");
		byte[] Ke = computeJFKSharedKey(computedExponential, nonceInitiator, nonceResponder, "1");
		byte[] Ka = computeJFKSharedKey(computedExponential, nonceInitiator, nonceResponder, "2");

		byte[] hmacKey = computeJFKSharedKey(computedExponential, nonceInitiator, nonceResponder, "3");
		byte[] ivKey = computeJFKSharedKey(computedExponential, nonceInitiator, nonceResponder, "4");
		byte[] ivNonce = computeJFKSharedKey(computedExponential, nonceInitiator, nonceResponder, "5");

		/* Bytes  1-4:  Initial sequence number for the initiator
		 * Bytes  5-8:  Initial sequence number for the responder
		 * Bytes  9-12: Initial message id for the initiator
		 * Bytes 13-16: Initial message id for the responder
		 * Note that we are the responder */
		byte[] sharedData = computeJFKSharedKey(computedExponential, nonceInitiator, nonceResponder, "6");
		int theirInitialSeqNum = ((sharedData[0] & 0xFF) << 24)
				| ((sharedData[1] & 0xFF) << 16)
				| ((sharedData[2] & 0xFF) << 8)
				| (sharedData[3] & 0xFF);
		int ourInitialSeqNum = ((sharedData[4] & 0xFF) << 24)
				| ((sharedData[5] & 0xFF) << 16)
				| ((sharedData[6] & 0xFF) << 8)
				| (sharedData[7] & 0xFF);
		int theirInitialMsgID, ourInitialMsgID;
		if(negType >= 7) {
			theirInitialMsgID =
				unknownInitiator ? getInitialMessageID(crypto.myIdentity) :
					getInitialMessageID(pn.identity, crypto.myIdentity);
			ourInitialMsgID =
				unknownInitiator ? getInitialMessageID(crypto.myIdentity) :
					getInitialMessageID(crypto.myIdentity, pn.identity);
		} else {
			theirInitialMsgID= ((sharedData[8] & 0xFF) << 24)
				| ((sharedData[9] & 0xFF) << 16)
				| ((sharedData[10] & 0xFF) << 8)
				| (sharedData[11] & 0xFF);
			ourInitialMsgID= ((sharedData[12] & 0xFF) << 24)
				| ((sharedData[13] & 0xFF) << 16)
				| ((sharedData[14] & 0xFF) << 8)
				| (sharedData[15] & 0xFF);
		}
		if(logMINOR)
			Logger.minor(this, "Their initial message ID: "+theirInitialMsgID+" ours "+ourInitialMsgID);

		c.initialize(Ke);
		int ivLength = PCFBMode.lengthIV(c);
		int decypheredPayloadOffset = 0;
		// We compute the HMAC of ("I"+cyphertext) : the cyphertext includes the IV!
		byte[] decypheredPayload = Arrays.copyOf(JFK_PREFIX_INITIATOR, JFK_PREFIX_INITIATOR.length + payload.length - inputOffset);
		decypheredPayloadOffset += JFK_PREFIX_INITIATOR.length;
		System.arraycopy(payload, inputOffset, decypheredPayload, decypheredPayloadOffset, decypheredPayload.length-decypheredPayloadOffset);
		if(!HMAC.verifyWithSHA256(Ka, decypheredPayload, hmac)) {
			Logger.error(this, "The inner-HMAC doesn't match; let's discard the packet JFK(3) - "+peerTransport);
			return;
		}

		final PCFBMode pk = PCFBMode.create(c, decypheredPayload, decypheredPayloadOffset);
		// Get the IV
		decypheredPayloadOffset += ivLength;
		// Decrypt the payload
		pk.blockDecipher(decypheredPayload, decypheredPayloadOffset, decypheredPayload.length-decypheredPayloadOffset);
		/*
		 * DecipheredData Format:
		 * Signature-r,s
		 * Node Data (starting with BootID)
		 */
		byte[] r = Arrays.copyOfRange(decypheredPayload, decypheredPayloadOffset, decypheredPayloadOffset+Node.SIGNATURE_PARAMETER_LENGTH);
		decypheredPayloadOffset += Node.SIGNATURE_PARAMETER_LENGTH;
		byte[] s = Arrays.copyOfRange(decypheredPayload, decypheredPayloadOffset, decypheredPayloadOffset+Node.SIGNATURE_PARAMETER_LENGTH);
		decypheredPayloadOffset += Node.SIGNATURE_PARAMETER_LENGTH;
		byte[] data = Arrays.copyOfRange(decypheredPayload, decypheredPayloadOffset, decypheredPayload.length);
		int ptr = 0;
		long trackerID;
		trackerID = Fields.bytesToLong(data, ptr);
		if(trackerID < 0) trackerID = -1;
		ptr += 8;
		long bootID = Fields.bytesToLong(data, ptr);
		ptr += 8;
		byte[] hisRef = Arrays.copyOfRange(data, ptr, data.length);

		// construct the peernode
		if(unknownInitiator) {
			pn = getPeerNodeFromUnknownInitiator(hisRef, setupType, pn, replyTo);
			peerTransport = pn.getPeerTransport(sock);
		}
		if(pn == null) {
			if(unknownInitiator) {
				// Reject
				Logger.normal(this, "Rejecting... unable to construct PeerNode");
			} else {
				Logger.error(this, "PeerNode is null and unknownInitiator is false!");
			}
			return;
		}

		// verify the signature
		DSASignature remoteSignature = new DSASignature(new NativeBigInteger(1,r), new NativeBigInteger(1,s));
		if(!DSA.verify(pn.peerPubKey, remoteSignature, new NativeBigInteger(1, SHA256.digest(assembleDHParams(nonceInitiator, nonceResponder, initiatorExponential, responderExponential, crypto.myIdentity, data))), false)) {
			Logger.error(this, "The signature verification has failed!! JFK(3) - "+peerTransport.getAddress());
			return;
		}

		// At this point we know it's from the peer, so we can report a packet received.
		peerTransport.receivedPacket(true, false);

		BlockCipher outgoingCipher = null;
		BlockCipher incommingCipher = null;
		BlockCipher ivCipher = null;
		try {
			outgoingCipher = new Rijndael(256, 256);
			incommingCipher = new Rijndael(256, 256);
			ivCipher = new Rijndael(256, 256);
		} catch (UnsupportedCipherException e) {
			throw new RuntimeException(e);
		}
		outgoingCipher.initialize(outgoingKey);
		incommingCipher.initialize(incommingKey);
		ivCipher.initialize(ivKey);

		// Promote if necessary
		boolean dontWant = false;
		if(oldOpennetPeer) {
			OpennetManager opennet = node.getOpennet();
			if(opennet == null) {
				Logger.normal(this, "Dumping incoming old-opennet peer as opennet just turned off: "+pn+".");
				return;
			}
			/* When an old-opennet-peer connects, add it at the top of the LRU, so that it isn't
			 * immediately dropped when there is no droppable peer to drop. If it was dropped
			 * from the bottom of the LRU list, we would not have added it to the LRU; so it was
			 * somewhere in the middle. */
			if(!opennet.wantPeer(pn, false, false, true, ConnectionType.RECONNECT)) {
				Logger.normal(this, "No longer want peer "+pn+" - dumping it after connecting");
				dontWant = true;
				opennet.purgeOldOpennetPeer(pn);
			}
			// wantPeer will call node.peers.addPeer(), we don't have to.
		}
		boolean allow;
		try {
			allow = crypto.allowConnection(pn, replyTo.getFreenetAddress(), sock);
		}catch(UnsupportedIPAddressOperationException e) {
			allow = true;
			//Non IP based address. Assume that only one PeerNode is using it.
		}
		if((!dontWant) && !allow) {
			if(pn instanceof DarknetPeerNode) {
				Logger.error(this, "Dropping peer "+pn+" because don't want connection due to others on the same IP address!");
				System.out.println("Disconnecting permanently from your friend \""+((DarknetPeerNode)pn).getName()+"\" because other peers are using the same IP address!");
			}
			Logger.normal(this, "Rejecting connection because already have something with the same IP");
			dontWant = true;
		}

		long newTrackerID = pn.completedHandshake(
				bootID, hisRef, 0, hisRef.length, outgoingCipher, outgoingKey, incommingCipher,
				incommingKey, replyTo, true, negType, trackerID, false, false, hmacKey, ivCipher,
				ivNonce, ourInitialSeqNum, theirInitialSeqNum, ourInitialMsgID, theirInitialMsgID, sock);

		if(newTrackerID > 0) {

			// Send reply
			sendJFKMessage4(1, negType, 3, nonceInitiator, nonceResponder,initiatorExponential, responderExponential,
					c, Ke, Ka, authenticator, hisRef, peerTransport, replyTo, unknownInitiator, setupType, newTrackerID, newTrackerID == trackerID);

			if(dontWant) {
				node.peers.disconnectAndRemove(pn, true, true, true); // Let it connect then tell it to remove it.
			} else {
				pn.maybeSendInitialMessages(sock);
			}
		} else {
			Logger.error(this, "Handshake failure! with "+peerTransport.getAddress());
			// Don't send the JFK(4). We have not successfully connected.
		}

		final long t2=System.currentTimeMillis();
		if((t2-t1)>500) {
			Logger.error(this,"Message3 Processing packet for "+peerTransport.getAddress()+" took "+TimeUtil.formatTime(t2-t1, 3, true));
		}
	}

	private PeerNode getPeerNodeFromUnknownInitiator(byte[] hisRef, int setupType, PeerNode pn, PluginAddress from) {
		if(setupType == SETUP_OPENNET_SEEDNODE) {
			OpennetManager om = node.getOpennet();
			if(om == null) {
				Logger.error(this, "Opennet disabled, ignoring seednode connect attempt");
				// FIXME Send some sort of explicit rejection message.
				return null;
			}
			SimpleFieldSet ref = OpennetManager.validateNoderef(hisRef, 0, hisRef.length, null, true);
			if(ref == null) {
				Logger.error(this, "Invalid noderef");
				// FIXME Send some sort of explicit rejection message.
				return null;
			}
			PeerNode seed;
			try {
				seed = new SeedClientPeerNode(ref, node, crypto, node.peers, false, true);
				// Don't tell tracker yet as we don't have the address yet.
			} catch (FSParseException e) {
				Logger.error(this, "Invalid seed client noderef: "+e+" from "+from, e);
				return null;
			} catch (PeerParseException e) {
				Logger.error(this, "Invalid seed client noderef: "+e+" from "+from, e);
				return null;
			} catch (ReferenceSignatureVerificationException e) {
				Logger.error(this, "Invalid seed client noderef: "+e+" from "+from, e);
				return null;
			}
			if(seed.equals(pn)) {
				Logger.normal(this, "Already connected to seednode");
				return pn;
			}
			node.peers.addPeer(seed);
			return seed;
		} else {
			Logger.error(this, "Unknown setup type");
			return null;
		}
	}

	/*
	 * Responder Method:Message4
	 * Process Message4
	 *
	 * Format:
	 * HMAC{Ka}[cyphertext]
	 * IV + E{Ke}[S{R}[Ni, Nr, g^i, g^r, IDi, bootID, znoderefR, znoderefI], bootID, znoderefR]
	 *
	 * @param payload The decrypted auth packet.
	 * @param pn The PeerNode we are talking to. Cannot be null as we are the initiator.
	 * @param replyTo The Peer we are replying to.
	 */
	private boolean processJFKMessage4(byte[] payload, int inputOffset, PeerPacketTransport peerTransport, PluginAddress replyTo, boolean oldOpennetPeer, boolean unknownInitiator, int setupType, int negType)
	{
		PeerNode pn = peerTransport.pn;
		final long t1 = System.currentTimeMillis();
		int modulusLength = getModulusLength(negType);
		if(logMINOR) Logger.minor(this, "Got a JFK(4) message, processing it - "+peerTransport.getAddress());
		if(peerTransport.jfkMyRef == null) {
			String error = "Got a JFK(4) message but no pn.jfkMyRef for "+peerTransport;
			if(node.getUptime() < 60*1000) {
				Logger.minor(this, error);
			} else {
				Logger.error(this, error);
			}
		}
		BlockCipher c = null;
		try { c = new Rijndael(256, 256); } catch (UnsupportedCipherException e) { throw new RuntimeException(e); }

		final int expectedLength =
			HASH_LENGTH + // HMAC of the cyphertext
			(c.getBlockSize() >> 3) + // IV
			Node.SIGNATURE_PARAMETER_LENGTH * 2 + // the signature
			9 + // ID of packet tracker, plus boolean byte
			8+ // bootID
			1; // znoderefR

		if(payload.length - inputOffset < expectedLength + 3) {
			Logger.error(this, "Packet too short from "+peerTransport.getAddress()+": "+payload.length+" after decryption in JFK(4), should be "+(expectedLength + 3));
			return false;
		}
		byte[] jfkBuffer = peerTransport.getJFKBuffer();
		if(jfkBuffer == null) {
			Logger.normal(this, "We have already handled this message... might be a replay or a bug - "+peerTransport);
			return false;
		}

		byte[] hmac = Arrays.copyOfRange(payload, inputOffset, inputOffset+HASH_LENGTH);
		inputOffset += HASH_LENGTH;

		c.initialize(peerTransport.jfkKe);
		int ivLength = PCFBMode.lengthIV(c);
		int decypheredPayloadOffset = 0;
		// We compute the HMAC of ("R"+cyphertext) : the cyphertext includes the IV!
		byte[] decypheredPayload = Arrays.copyOf(JFK_PREFIX_RESPONDER, JFK_PREFIX_RESPONDER.length + payload.length - inputOffset);
		decypheredPayloadOffset += JFK_PREFIX_RESPONDER.length;
		System.arraycopy(payload, inputOffset, decypheredPayload, decypheredPayloadOffset, payload.length-inputOffset);
		if(!HMAC.verifyWithSHA256(peerTransport.jfkKa, decypheredPayload, hmac)) {
			Logger.normal(this, "The digest-HMAC doesn't match; let's discard the packet - "+peerTransport.getAddress());
			return false;
		}

		// Try to find the HMAC in the cache:
		// If it is already present it indicates duplicate/replayed message4 and we can discard
		// If it's not, we can add it with a timestamp
		byte[] message4Timestamp = null;
		synchronized (authenticatorCache) {
			ByteArrayWrapper hmacBAW = new ByteArrayWrapper(hmac);
			message4Timestamp = authenticatorCache.get(hmacBAW);
			if(message4Timestamp == null) { // normal behaviour
				authenticatorCache.put(hmacBAW, Fields.longToBytes(t1));
			}
		}
		if(message4Timestamp != null) {
			Logger.normal(this, "We got a replayed message4 (first handled at "+TimeUtil.formatTime(t1-Fields.bytesToLong(message4Timestamp))+") from - "+peerTransport);
			return true;
		}

		// Get the IV
		final PCFBMode pk = PCFBMode.create(c, decypheredPayload, decypheredPayloadOffset);
		decypheredPayloadOffset += ivLength;
		// Decrypt the payload
		pk.blockDecipher(decypheredPayload, decypheredPayloadOffset, decypheredPayload.length - decypheredPayloadOffset);
		/*
		 * DecipheredData Format:
		 * Signature-r,s
		 * bootID, znoderef
		 */
		byte[] r = Arrays.copyOfRange(decypheredPayload, decypheredPayloadOffset, decypheredPayloadOffset+Node.SIGNATURE_PARAMETER_LENGTH);
		decypheredPayloadOffset += Node.SIGNATURE_PARAMETER_LENGTH;
		byte[] s = Arrays.copyOfRange(decypheredPayload, decypheredPayloadOffset, decypheredPayloadOffset+Node.SIGNATURE_PARAMETER_LENGTH);
		decypheredPayloadOffset += Node.SIGNATURE_PARAMETER_LENGTH;
		byte[] data = Arrays.copyOfRange(decypheredPayload, decypheredPayloadOffset, decypheredPayload.length);
		int ptr = 0;
		long trackerID;
		boolean reusedTracker;
		trackerID = Fields.bytesToLong(data, ptr);
		ptr += 8;
		reusedTracker = data[ptr++] != 0;
		long bootID = Fields.bytesToLong(data, ptr);
		ptr += 8;
		byte[] hisRef = Arrays.copyOfRange(data, ptr, data.length);

		// verify the signature
		DSASignature remoteSignature = new DSASignature(new NativeBigInteger(1,r), new NativeBigInteger(1,s));
		int dataLen = hisRef.length + 8 + 9;
		byte[] locallyGeneratedText = new byte[NONCE_SIZE * 2 + modulusLength * 2 + crypto.myIdentity.length + dataLen + peerTransport.jfkMyRef.length];
		int bufferOffset = NONCE_SIZE * 2 + modulusLength*2;
		System.arraycopy(jfkBuffer, 0, locallyGeneratedText, 0, bufferOffset);
		byte[] identity = crypto.getIdentity(unknownInitiator);
		System.arraycopy(identity, 0, locallyGeneratedText, bufferOffset, identity.length);
		bufferOffset += identity.length;
		// bootID
		System.arraycopy(data, 0, locallyGeneratedText, bufferOffset, dataLen);
		bufferOffset += dataLen;
		System.arraycopy(peerTransport.jfkMyRef, 0, locallyGeneratedText, bufferOffset, peerTransport.jfkMyRef.length);
		byte[] messageHash = SHA256.digest(locallyGeneratedText);
		if(!DSA.verify(peerTransport.pn.peerPubKey, remoteSignature, new NativeBigInteger(1, messageHash), false)) {
			String error = "The signature verification has failed!! JFK(4) -"+peerTransport.getAddress()+" message hash "+HexUtil.bytesToHex(messageHash)+" length "+locallyGeneratedText.length+" hisRef "+hisRef.length+" hash "+Fields.hashCode(hisRef)+" myRef "+peerTransport.jfkMyRef.length+" hash "+Fields.hashCode(peerTransport.jfkMyRef)+" boot ID "+bootID;
			Logger.error(this, error);
			return true;
		}

		// Received a packet
		peerTransport.receivedPacket(true, false);

		// Promote if necessary
		boolean dontWant = false;
		if(oldOpennetPeer) {
			OpennetManager opennet = node.getOpennet();
			if(opennet == null) {
				Logger.normal(this, "Dumping incoming old-opennet peer as opennet just turned off: "+pn+".");
				return true;
			}
			/* When an old-opennet-peer connects, add it at the top of the LRU, so that it isn't
			 * immediately dropped when there is no droppable peer to drop. If it was dropped
			 * from the bottom of the LRU list, we would not have added it to the LRU; so it was
			 * somewhere in the middle. */
			if(!opennet.wantPeer(pn, false, false, true, ConnectionType.RECONNECT)) {
				Logger.normal(this, "No longer want peer "+pn+" - dumping it after connecting");
				dontWant = true;
				opennet.purgeOldOpennetPeer(pn);
			}
			// wantPeer will call node.peers.addPeer(), we don't have to.
		}
		boolean allow;
		try {
			allow = crypto.allowConnection(pn, replyTo.getFreenetAddress(), sock);
		}catch(UnsupportedIPAddressOperationException e) {
			allow = true;
			//Non IP based address. Assume that only one PeerNode is using it.
		}
		if((!dontWant) && !allow) {
			Logger.normal(this, "Rejecting connection because already have something with the same IP");
			dontWant = true;
		}

		// We change the key
		BlockCipher ivCipher = null;
		BlockCipher outgoingCipher = null;
		BlockCipher incommingCipher = null;
		try {
			ivCipher = new Rijndael(256, 256);
			outgoingCipher = new Rijndael(256, 256);
			incommingCipher = new Rijndael(256, 256);
		} catch (UnsupportedCipherException e) {
			throw new RuntimeException(e);
		}

		outgoingCipher.initialize(peerTransport.outgoingKey);
		incommingCipher.initialize(peerTransport.incommingKey);
		ivCipher.initialize(peerTransport.ivKey);

		long newTrackerID = pn.completedHandshake(
				bootID, hisRef, 0, hisRef.length, outgoingCipher, peerTransport.outgoingKey, incommingCipher,
				peerTransport.incommingKey, replyTo, false, negType, trackerID, true, reusedTracker, peerTransport.hmacKey,
				ivCipher, peerTransport.ivNonce, peerTransport.ourInitialSeqNum, peerTransport.theirInitialSeqNum, peerTransport.ourInitialMsgID,
				peerTransport.theirInitialMsgID, sock);
		if(newTrackerID >= 0) {
			if(dontWant) {
				node.peers.disconnectAndRemove(pn, true, true, true);
			} else {
				pn.maybeSendInitialMessages(sock);
			}
		} else {
			Logger.error(this, "Handshake failed!");
		}

		// cleanup
		// FIXME: maybe we should copy zeros/garbage into it before leaving it to the GC
		peerTransport.setJFKBuffer(null);
		peerTransport.jfkKa = null;
		peerTransport.jfkKe = null;
		peerTransport.outgoingKey = null;
		peerTransport.incommingKey = null;
		peerTransport.hmacKey = null;
		peerTransport.ivKey = null;
		peerTransport.ivNonce = null;
		peerTransport.ourInitialSeqNum = 0;
		peerTransport.theirInitialSeqNum = 0;
		peerTransport.ourInitialMsgID = 0;
		peerTransport.theirInitialMsgID = 0;
		// We want to clear it here so that new handshake requests
		// will be sent with a different DH pair
		peerTransport.setKeyAgreementSchemeContext(null);
		synchronized (peerTransport) {
			// FIXME TRUE MULTI-HOMING: winner-takes-all, kill all other connection attempts since we can't deal with multiple active connections
			// Also avoids leaking
			peerTransport.jfkNoncesSent.clear();
		}

		final long t2=System.currentTimeMillis();
		if((t2-t1)>500)
			Logger.error(this,"Message4 timeout error:Processing packet from "+peerTransport.getAddress());
		return true;
	}

	/*
	 * Format:
	 * Ni, Nr, g^i, g^r
	 * Authenticator - HMAC{Hkr}[g^r, g^i, Nr, Ni, IPi]
	 * HMAC{Ka}(cyphertext)
	 * IV + E{KE}[S{i}[Ni,Nr,g^i,g^r,idR, bootID, znoderefI], bootID, znoderefI]
	 *
	 * @param pn The PeerNode to encrypt the message for. Cannot be null as we are the initiator.
	 * @param replyTo The Peer to send the packet to.
	 */

	private void sendJFKMessage3(int version,final int negType,int phase,byte[] nonceInitiator,byte[] nonceResponder,byte[] hisExponential, byte[] authenticator, final PeerPacketTransport peerTransport, final PluginAddress replyTo, final boolean unknownInitiator, final int setupType)
	{
		final PeerNode pn = peerTransport.pn;
		if(logMINOR) Logger.minor(this, "Sending a JFK(3) message to "+peerTransport.getAddress());
		int modulusLength = getModulusLength(negType);
		long t1=System.currentTimeMillis();
		BlockCipher c = null;
		try { c = new Rijndael(256, 256); } catch (UnsupportedCipherException e) { throw new RuntimeException(e); }
		KeyAgreementSchemeContext ctx = peerTransport.getKeyAgreementSchemeContext();
		if(ctx == null) return;
		byte[] ourExponential = ctx.getPublicKeyNetworkFormat();
		peerTransport.jfkMyRef = unknownInitiator ? crypto.myCompressedHeavySetupRef() : crypto.myCompressedSetupRef();
		byte[] data = new byte[8 + 8 + peerTransport.jfkMyRef.length];
		int ptr = 0;
		long trackerID;
		trackerID = peerTransport.getReusableTrackerID();
		System.arraycopy(Fields.longToBytes(trackerID), 0, data, ptr, 8);
		ptr += 8;
		if(logMINOR) Logger.minor(this, "Sending tracker ID "+trackerID+" in JFK(3)");
		System.arraycopy(Fields.longToBytes(pn.getOutgoingBootID()), 0, data, ptr, 8);
		ptr += 8;
		System.arraycopy(peerTransport.jfkMyRef, 0, data, ptr, peerTransport.jfkMyRef.length);
		final byte[] message3 = new byte[NONCE_SIZE*2 + // nI, nR
		                           modulusLength*2 + // g^i, g^r
		                           HASH_LENGTH + // authenticator
		                           HASH_LENGTH + // HMAC(cyphertext)
		                           (c.getBlockSize() >> 3) + // IV
		                           Node.SIGNATURE_PARAMETER_LENGTH * 2 + // Signature (R,S)
		                           data.length]; // The bootid+noderef
		int offset = 0;
		// Ni
		System.arraycopy(nonceInitiator, 0, message3, offset, NONCE_SIZE);
		offset += NONCE_SIZE;
		// Nr
		System.arraycopy(nonceResponder, 0, message3, offset, NONCE_SIZE);
		offset += NONCE_SIZE;
		// g^i
		System.arraycopy(ourExponential, 0,message3, offset, ourExponential.length);
		offset += ourExponential.length;
		// g^r
		System.arraycopy(hisExponential, 0,message3, offset, hisExponential.length);
		offset += hisExponential.length;

		// Authenticator
		System.arraycopy(authenticator, 0, message3, offset, HASH_LENGTH);
		offset += HASH_LENGTH;
		/*
		 * Digital Signature of the message with the private key belonging to the initiator/responder
		 * It is assumed to be non-message recovering
		 */
		// save parameters so that we can verify message4
		byte[] toSign = assembleDHParams(nonceInitiator, nonceResponder, ourExponential, hisExponential, pn.identity, data);
		peerTransport.setJFKBuffer(toSign);
		DSASignature localSignature = crypto.sign(SHA256.digest(toSign));
		byte[] r = localSignature.getRBytes(Node.SIGNATURE_PARAMETER_LENGTH);
		byte[] s = localSignature.getSBytes(Node.SIGNATURE_PARAMETER_LENGTH);

		byte[] computedExponential;
		if (negType < 8 ) { // Legacy DH
		    NativeBigInteger _hisExponential = new NativeBigInteger(1,hisExponential);
		    computedExponential= ((DiffieHellmanLightContext)ctx).getHMACKey(_hisExponential);
		}else {
		    computedExponential = ((ECDHLightContext)ctx).getHMACKey(ECDH.getPublicKey(hisExponential)).getEncoded();
		}
		if(logDEBUG) Logger.debug(this, "The shared Master secret is : "+HexUtil.bytesToHex(computedExponential)+ " for " + peerTransport);
		/* 0 is the outgoing key for the initiator, 7 for the responder */
		peerTransport.outgoingKey = computeJFKSharedKey(computedExponential, nonceInitiator, nonceResponder, "0");
		peerTransport.incommingKey = computeJFKSharedKey(computedExponential, nonceInitiator, nonceResponder, "7");
		peerTransport.jfkKe = computeJFKSharedKey(computedExponential, nonceInitiator, nonceResponder, "1");
		peerTransport.jfkKa = computeJFKSharedKey(computedExponential, nonceInitiator, nonceResponder, "2");

		peerTransport.hmacKey = computeJFKSharedKey(computedExponential, nonceInitiator, nonceResponder, "3");
		peerTransport.ivKey = computeJFKSharedKey(computedExponential, nonceInitiator, nonceResponder, "4");
		peerTransport.ivNonce = computeJFKSharedKey(computedExponential, nonceInitiator, nonceResponder, "5");

		/* Bytes  1-4:  Initial sequence number for the initiator
		 * Bytes  5-8:  Initial sequence number for the responder
		 * Bytes  9-12: Initial message id for the initiator
		 * Bytes 13-16: Initial message id for the responder
		 * Note that we are the initiator */
		byte[] sharedData = computeJFKSharedKey(computedExponential, nonceInitiator, nonceResponder, "6");
		peerTransport.ourInitialSeqNum = ((sharedData[0] & 0xFF) << 24)
				| ((sharedData[1] & 0xFF) << 16)
				| ((sharedData[2] & 0xFF) << 8)
				| (sharedData[3] & 0xFF);
		peerTransport.theirInitialSeqNum = ((sharedData[4] & 0xFF) << 24)
				| ((sharedData[5] & 0xFF) << 16)
				| ((sharedData[6] & 0xFF) << 8)
				| (sharedData[7] & 0xFF);
		if(negType >= 7) {
			peerTransport.theirInitialMsgID =
				unknownInitiator ? getInitialMessageID(pn.identity) :
					getInitialMessageID(pn.identity, crypto.myIdentity);
				peerTransport.ourInitialMsgID =
				unknownInitiator ? getInitialMessageID(pn.identity) :
					getInitialMessageID(crypto.myIdentity, pn.identity);
		} else {
			peerTransport.ourInitialMsgID= ((sharedData[8] & 0xFF) << 24)
				| ((sharedData[9] & 0xFF) << 16)
				| ((sharedData[10] & 0xFF) << 8)
				| (sharedData[11] & 0xFF);
			peerTransport.theirInitialMsgID= ((sharedData[12] & 0xFF) << 24)
				| ((sharedData[13] & 0xFF) << 16)
				| ((sharedData[14] & 0xFF) << 8)
				| (sharedData[15] & 0xFF);
		}
			
		if(logMINOR)
			Logger.minor(this, "Their initial message ID: "+peerTransport.theirInitialMsgID+" ours "+peerTransport.ourInitialMsgID);


		c.initialize(peerTransport.jfkKe);
		int ivLength = PCFBMode.lengthIV(c);
		byte[] iv = new byte[ivLength];
		node.random.nextBytes(iv);
		PCFBMode pcfb = PCFBMode.create(c, iv);
		int cleartextOffset = 0;
		byte[] cleartext = new byte[JFK_PREFIX_INITIATOR.length + ivLength + Node.SIGNATURE_PARAMETER_LENGTH * 2 + data.length];
		System.arraycopy(JFK_PREFIX_INITIATOR, 0, cleartext, cleartextOffset, JFK_PREFIX_INITIATOR.length);
		cleartextOffset += JFK_PREFIX_INITIATOR.length;
		System.arraycopy(iv, 0, cleartext, cleartextOffset, ivLength);
		cleartextOffset += ivLength;
		System.arraycopy(r, 0, cleartext, cleartextOffset, Node.SIGNATURE_PARAMETER_LENGTH);
		cleartextOffset += Node.SIGNATURE_PARAMETER_LENGTH;
		System.arraycopy(s, 0, cleartext, cleartextOffset, Node.SIGNATURE_PARAMETER_LENGTH);
		cleartextOffset += Node.SIGNATURE_PARAMETER_LENGTH;
		System.arraycopy(data, 0, cleartext, cleartextOffset, data.length);
		cleartextOffset += data.length;

		int cleartextToEncypherOffset = JFK_PREFIX_INITIATOR.length + ivLength;
		pcfb.blockEncipher(cleartext, cleartextToEncypherOffset, cleartext.length-cleartextToEncypherOffset);

		// We compute the HMAC of (prefix + cyphertext) Includes the IV!
		byte[] hmac = HMAC.macWithSHA256(peerTransport.jfkKa, cleartext, HASH_LENGTH);

		// copy stuffs back to the message
		System.arraycopy(hmac, 0, message3, offset, HASH_LENGTH);
		offset += HASH_LENGTH;
		System.arraycopy(iv, 0, message3, offset, ivLength);
		offset += ivLength;
		System.arraycopy(cleartext, cleartextToEncypherOffset, message3, offset, cleartext.length-cleartextToEncypherOffset);

		// cache the message
		synchronized (authenticatorCache) {
			if(!maybeResetTransientKey())
				authenticatorCache.put(new ByteArrayWrapper(authenticator),message3);
		}
		final long timeSent = System.currentTimeMillis();
		if(unknownInitiator) {
			sendAnonAuthPacket(1, negType, 2, setupType, message3, peerTransport, replyTo, pn.anonymousInitiatorSetupCipher);
		} else {
			sendAuthPacket(1, negType, 2, message3, peerTransport, replyTo);
		}

		/* Re-send the packet after 5sec if we don't get any reply */
		node.getTicker().queueTimedJob(new Runnable() {
			@Override
			public void run() {
				if(peerTransport.timeLastTransportConnectionCompleted() < timeSent) {
					if(logMINOR) Logger.minor(this, "Resending JFK(3) to "+peerTransport+" for "+node.getDarknetPortNumber());
					if(unknownInitiator) {
						sendAnonAuthPacket(1, negType, 2, setupType, message3, peerTransport, replyTo, pn.anonymousInitiatorSetupCipher);
					} else {
						sendAuthPacket(1, negType, 2, message3, peerTransport, replyTo);
					}
				}
			}
		}, 5*1000);
		long t2=System.currentTimeMillis();
		if((t2-t1)>500)
			Logger.error(this,"Message3 timeout error:Sending packet for "+peerTransport.getAddress());
	}

	private int getInitialMessageID(byte[] identity) {
		MessageDigest md = SHA256.getMessageDigest();
		md.update(identity);
		// Similar to JFK keygen, should be safe enough.
		try {
			md.update("INITIAL0".getBytes("UTF-8"));
		} catch (UnsupportedEncodingException e) {
			throw new Error(e);
		}
		byte[] hashed = md.digest();
		SHA256.returnMessageDigest(md);
		return Fields.bytesToInt(hashed, 0);
	}

	private int getInitialMessageID(byte[] identity, byte[] otherIdentity) {
		MessageDigest md = SHA256.getMessageDigest();
		md.update(identity);
		md.update(otherIdentity);
		// Similar to JFK keygen, should be safe enough.
		try {
			md.update("INITIAL1".getBytes("UTF-8"));
		} catch (UnsupportedEncodingException e) {
			throw new Error(e);
		}
		byte[] hashed = md.digest();
		SHA256.returnMessageDigest(md);
		return Fields.bytesToInt(hashed, 0);
	}

	/*
	 * Format:
	 * HMAC{Ka}(cyphertext)
	 * IV, E{Ke}[S{R}[Ni,Nr,g^i,g^r,idI, bootID, znoderefR, znoderefI],bootID,znoderefR]
	 *
	 * @param replyTo The address we are replying to.
	 * @param pn The PeerNode to encrypt the auth packet to. Cannot be null, because even in anonymous initiator,
	 * we will have created one before calling this method.
	 */
	private void sendJFKMessage4(int version,int negType,int phase,byte[] nonceInitiator,byte[] nonceResponder,byte[] initiatorExponential,byte[] responderExponential, BlockCipher c, byte[] Ke, byte[] Ka, byte[] authenticator, byte[] hisRef, PeerPacketTransport peerTransport, PluginAddress replyTo, boolean unknownInitiator, int setupType, long newTrackerID, boolean sameAsOldTrackerID) {
		PeerNode pn = peerTransport.pn;
		if(logMINOR)
			Logger.minor(this, "Sending a JFK(4) message to "+peerTransport.getAddress());
		long t1=System.currentTimeMillis();

		byte[] myRef = crypto.myCompressedSetupRef();
		byte[] data = new byte[9 + 8 + myRef.length + hisRef.length];
		int ptr = 0;
		System.arraycopy(Fields.longToBytes(newTrackerID), 0, data, ptr, 8);
		ptr += 8;
		data[ptr++] = (byte) (sameAsOldTrackerID ? 1 : 0);

		System.arraycopy(Fields.longToBytes(pn.getOutgoingBootID()), 0, data, ptr, 8);
		ptr += 8;
		System.arraycopy(myRef, 0, data, ptr, myRef.length);
		ptr += myRef.length;
		System.arraycopy(hisRef, 0, data, ptr, hisRef.length);

		byte[] params = assembleDHParams(nonceInitiator, nonceResponder, initiatorExponential, responderExponential, pn.identity, data);
		byte[] messageHash = SHA256.digest(params);
		if(logMINOR)
			Logger.minor(this, "Message hash: "+HexUtil.bytesToHex(messageHash)+" length "+params.length+" myRef: "+myRef.length+" hash "+Fields.hashCode(myRef)+" hisRef: "+hisRef.length+" hash "+Fields.hashCode(hisRef)+" boot ID "+node.bootID);
		DSASignature localSignature = crypto.sign(messageHash);
		byte[] r = localSignature.getRBytes(Node.SIGNATURE_PARAMETER_LENGTH);
		byte[] s = localSignature.getSBytes(Node.SIGNATURE_PARAMETER_LENGTH);

		int ivLength = PCFBMode.lengthIV(c);
		byte[] iv=new byte[ivLength];
		node.random.nextBytes(iv);
		PCFBMode pk=PCFBMode.create(c, iv);
		// Don't include the last bit
		int dataLength = data.length - hisRef.length;
		byte[] cyphertext = new byte[JFK_PREFIX_RESPONDER.length + ivLength + Node.SIGNATURE_PARAMETER_LENGTH * 2 +
		                             dataLength];
		int cleartextOffset = 0;
		System.arraycopy(JFK_PREFIX_RESPONDER, 0, cyphertext, cleartextOffset, JFK_PREFIX_RESPONDER.length);
		cleartextOffset += JFK_PREFIX_RESPONDER.length;
		System.arraycopy(iv, 0, cyphertext, cleartextOffset, ivLength);
		cleartextOffset += ivLength;
		System.arraycopy(r, 0, cyphertext, cleartextOffset, Node.SIGNATURE_PARAMETER_LENGTH);
		cleartextOffset += Node.SIGNATURE_PARAMETER_LENGTH;
		System.arraycopy(s, 0, cyphertext, cleartextOffset, Node.SIGNATURE_PARAMETER_LENGTH);
		cleartextOffset += Node.SIGNATURE_PARAMETER_LENGTH;
		System.arraycopy(data, 0, cyphertext, cleartextOffset, dataLength);
		cleartextOffset += dataLength;
		// Now encrypt the cleartext[Signature]
		int cleartextToEncypherOffset = JFK_PREFIX_RESPONDER.length + ivLength;
		pk.blockEncipher(cyphertext, cleartextToEncypherOffset, cyphertext.length - cleartextToEncypherOffset);

		// We compute the HMAC of (prefix + iv + signature)
		byte[] hmac = HMAC.macWithSHA256(Ka, cyphertext, HASH_LENGTH);

		// Message4 = hmac + IV + encryptedSignature
		byte[] message4 = new byte[HASH_LENGTH + ivLength + (cyphertext.length - cleartextToEncypherOffset)];
		int offset = 0;
		System.arraycopy(hmac, 0, message4, offset, HASH_LENGTH);
		offset += HASH_LENGTH;
		System.arraycopy(iv, 0, message4, offset, ivLength);
		offset += ivLength;
		System.arraycopy(cyphertext, cleartextToEncypherOffset, message4, offset, cyphertext.length - cleartextToEncypherOffset);

		// cache the message
		synchronized (authenticatorCache) {
			if(!maybeResetTransientKey())
				authenticatorCache.put(new ByteArrayWrapper(authenticator), message4);
			if(logDEBUG) Logger.debug(this, "Storing JFK(4) for "+HexUtil.bytesToHex(authenticator));
		}

		if(unknownInitiator) {
			sendAnonAuthPacket(1, negType, 3, setupType, message4, peerTransport, replyTo, crypto.anonSetupCipher);
		} else {
			sendAuthPacket(1, negType, 3, message4, peerTransport, replyTo);
		}
		long t2=System.currentTimeMillis();
		if((t2-t1)>500)
			Logger.error(this,"Message4 timeout error:Sending packet for "+peerTransport.getAddress());
	}

	/**
	 * Send an auth packet.
	 */
	private void sendAuthPacket(int version, int negType, int phase, byte[] data, PeerPacketTransport peerTransport, PluginAddress replyTo) {
		if(peerTransport == null) throw new IllegalArgumentException("peerTransport shouldn't be null here!");
		byte[] output = new byte[data.length+3];
		output[0] = (byte) version;
		output[1] = (byte) negType;
		output[2] = (byte) phase;
		System.arraycopy(data, 0, output, 3, data.length);
		if(logMINOR) {
			long now = System.currentTimeMillis();
			String delta = "never";
			long last = peerTransport.lastSentTransportPacketTime();
			delta = TimeUtil.formatTime(now - last, 2, true) + " ago";
			Logger.minor(this, "Sending auth packet for "+ String.valueOf(peerTransport.getAddress())+" (phase="+phase+", ver="+version+", nt="+negType+") (last packet sent "+delta+") to "+replyTo+" data.length="+data.length+" to "+replyTo);
		}
		sendAuthPacket(output, peerTransport.pn.outgoingSetupCipher, peerTransport, replyTo, false);
	}

	/**
	 * @param version
	 * @param negType
	 * @param phase
	 * @param setupType
	 * @param data
	 * @param peerTransport May be null. If not null, used for details such as anti-firewall hacks.
	 * @param replyTo
	 * @param cipher
	 */
	private void sendAnonAuthPacket(int version, int negType, int phase, int setupType, byte[] data, PeerPacketTransport peerTransport, PluginAddress replyTo, BlockCipher cipher) {
		byte[] output = new byte[data.length+4];
		output[0] = (byte) version;
		output[1] = (byte) negType;
		output[2] = (byte) phase;
		output[3] = (byte) setupType;
		System.arraycopy(data, 0, output, 4, data.length);
		if(logMINOR) Logger.minor(this, "Sending anon auth packet (phase="+phase+", ver="+version+", nt="+negType+", setup="+setupType+") data.length="+data.length);
		sendAuthPacket(output, cipher, peerTransport, replyTo, true);
	}

	/**
	 * Send an auth packet (we have constructed the payload, now hash it, pad it, encrypt it).
	 */
	private void sendAuthPacket(byte[] output, BlockCipher cipher, PeerPacketTransport peerTransport, PluginAddress replyTo, boolean anonAuth) {
		int length = output.length;
		if(length > sock.getMaxPacketSize()) {
			throw new IllegalStateException("Cannot send auth packet: too long: "+length);
		}
		byte[] iv = new byte[PCFBMode.lengthIV(cipher)];
		node.random.nextBytes(iv);
		byte[] hash = SHA256.digest(output);
		if(logMINOR) Logger.minor(this, "Data hash: "+HexUtil.bytesToHex(hash));
		int prePaddingLength = iv.length + hash.length + 2 /* length */ + output.length;
		int maxPacketSize = sock.getMaxPacketSize() - sock.getHeadersLength();
		int paddingLength;
		if(prePaddingLength < maxPacketSize) {
			paddingLength = node.fastWeakRandom.nextInt(Math.min(100, maxPacketSize - prePaddingLength));
		} else {
			paddingLength = 0; // Avoid oversize packets if at all possible, the MTU is an estimate and may be wrong, and fragmented packets are often dropped by firewalls.
			// Tell the devs, this shouldn't happen.
			Logger.error(this, "Warning: sending oversize auth packet (anonAuth="+anonAuth+") of "+prePaddingLength+" bytes!");
		}
		if(paddingLength < 0) paddingLength = 0;
		byte[] data = new byte[prePaddingLength + paddingLength];
		PCFBMode pcfb = PCFBMode.create(cipher, iv);
		System.arraycopy(iv, 0, data, 0, iv.length);
		pcfb.blockEncipher(hash, 0, hash.length);
		System.arraycopy(hash, 0, data, iv.length, hash.length);
		if(logMINOR) Logger.minor(this, "Payload length: "+length+" padded length "+data.length);
		data[hash.length+iv.length] = (byte) pcfb.encipher((byte)(length>>8));
		data[hash.length+iv.length+1] = (byte) pcfb.encipher((byte)length);
		pcfb.blockEncipher(output, 0, output.length);
		System.arraycopy(output, 0, data, hash.length+iv.length+2, output.length);

		Util.randomBytes(node.fastWeakRandom, data, hash.length+iv.length+2+output.length, paddingLength);
		node.nodeStats.reportAuthBytes(data.length + sock.getHeadersLength());
		try {
			sendPacket(data, replyTo, peerTransport);
		} catch (LocalAddressException e) {
			Logger.warning(this, "Tried to send auth packet to local address: "+replyTo+" for "+peerTransport+" - maybe you should set allowLocalAddresses for this peer??");
		}
	}

	private void sendPacket(byte[] data, PluginAddress replyTo, PeerPacketTransport peerTransport) throws LocalAddressException {
		String transportName = "unknown"; //Logging
		if(peerTransport != null) {
			transportName = peerTransport.transportName;
			PeerNode pn = peerTransport.pn;
			if(pn != null) {
				if(pn.isIgnoreSource()) {
					PluginAddress p = peerTransport.getAddress();
					if(p != null) replyTo = p;
				}
			}
		}
		try {
			sock.sendPacket(data, replyTo, peerTransport == null ? crypto.config.alwaysAllowLocalAddresses() : peerTransport.allowLocalAddresses());
		} catch (MalformedPluginAddressException e) {
			Logger.error(this, "replyTo can't be null. This means that the object is not the instance " 
					+ transportName + " plugin created. Something went wrong", e);
		}
		if(peerTransport != null)
			peerTransport.reportOutgoingBytes(data.length);
		if(PeerNode.shouldThrottle(replyTo, node)) {
			node.outputThrottle.forceGrab(data.length);
		}
	}

	/**
	 * Should we log an error for an event that could easily be
	 * caused by a handshake across a restart boundary?
	 */
	private boolean shouldLogErrorInHandshake(long now) {
		if(now - node.startupTime < Node.HANDSHAKE_TIMEOUT*2)
			return false;
		return true;
	}

	byte[] preformat(byte[] buf, int offset, int length) {
		byte[] newBuf;
		if(buf != null) {
			newBuf = new byte[length+3];
			newBuf[0] = 1;
			newBuf[1] = (byte)(length >> 8);
			newBuf[2] = (byte)length;
			System.arraycopy(buf, offset, newBuf, 3, length);
		} else {
			newBuf = new byte[1];
			newBuf[0] = 0;
		}
		return newBuf;
	}

	protected String l10n(String key, String[] patterns, String[] values) {
		return NodeL10n.getBase().getString("FNPPacketMangler."+key, patterns, values);
	}

	protected String l10n(String key, String pattern, String value) {
		return NodeL10n.getBase().getString("FNPPacketMangler."+key, pattern, value);
	}

	/* (non-Javadoc)
	 * @see freenet.node.OutgoingPacketMangler#sendHandshake(freenet.node.PeerNode)
	 */
	@Override
	public void sendHandshake(PeerNode pn, boolean notRegistered) {
		PeerPacketTransport peerTransport = pn.getPeerTransport(sock);
		int negType = pn.selectNegType(this);
		if(negType == -1) {
			// Pick a random negType from what I do support
			int[] negTypes = supportedNegTypes(true);
			negType = negTypes[node.random.nextInt(negTypes.length)];
			Logger.normal(this, "Cannot send handshake to "+pn+" because no common negTypes, choosing random negType of "+negType);
		}
		if(logMINOR) Logger.minor(this, "Possibly sending handshake to "+pn+" negotiation type "+negType);

		PluginAddress address = peerTransport.getHandshakeAddress();
		if(address == null) {
			pn.couldNotSendHandshake(notRegistered, sock);
			return;
		}
		PluginAddress oldAddress = address;
		try {
			address = address.dropHostName();
		}catch(UnsupportedIPAddressOperationException e) {
			//Do nothing. Non IP based address.
		}
		if(address == null) {
			Logger.error(this, "No address for peer "+oldAddress+" so cannot send handshake");
			pn.couldNotSendHandshake(notRegistered, sock);
			return;
		}
		try {
			sendJFKMessage1(peerTransport, address, pn.handshakeUnknownInitiator(), pn.handshakeSetupType(), negType);
		} catch (NoContextsException e) {
			handleNoContextsException(e, NoContextsException.CONTEXT.SENDING);
			return;
		}
		if(logMINOR)
			Logger.minor(this, "Sending handshake to "+address+" for "+pn);
		pn.sentHandshake(notRegistered, sock);
	}

	/* (non-Javadoc)
	 * @see freenet.node.OutgoingPacketMangler#isDisconnected(freenet.io.comm.PeerContext)
	 */
	@Override
	public boolean isDisconnected(PeerContext context) {
		if(context == null) return false;
		return !context.isConnected();
	}
	
	/**
	 * List of supported negotiation types in preference order (best last)
	 * Assumes bcProv loaded successfully.
	 * This method must eventually be moved out 
	 * as it is common to both streams and packets
	 */
	public static int[] supportedNegTypes(boolean forPublic) {
		if(forPublic)
			return new int[] { 6, 7, 8 };
		else
			return new int[] { 7, 8 };
	}

	@Override
	public int fullHeadersLengthOneMessage() {
		return fullHeadersLengthOneMessage;
	}

	@Override
	public SocketHandler getSocketHandler() {
		return sock;
	}
	
	@Override
	public PacketTransportPlugin getTransport() {
		return sock;
	}

	@Override
	public Peer[] getPrimaryIPAddress() {
		return crypto.detector.getPrimaryPeers();
	}

	@Override
	public byte[] getCompressedNoderef() {
		return crypto.myCompressedFullRef();
	}

	@Override
	public boolean alwaysAllowLocalAddresses() {
		return crypto.config.alwaysAllowLocalAddresses();
	}

	private DiffieHellmanLightContext _genLightDiffieHellmanContext() {
		final DiffieHellmanLightContext ctx = DiffieHellman.generateLightContext(dhGroupToUse);
		ctx.setSignature(crypto.sign(SHA256.digest(assembleDHParams(ctx.getPublicKeyNetworkFormat(), crypto.getCryptoGroup()))));

		return ctx;
	}
    
	private ECDHLightContext _genECDHLightContext() {
        final ECDHLightContext ctx = new ECDHLightContext(ecdhCurveToUse);
        ctx.setSignature(crypto.sign(SHA256.digest(assembleDHParams(ctx.getPublicKeyNetworkFormat(), crypto.getCryptoGroup()))));

        return ctx;
    }
	
	private void _fillJFKDHFIFOOffThread() {
		// do it off-thread
		node.executor.execute(new PrioRunnable() {
			@Override
			public void run() {
				_fillJFKDHFIFO();
			}
			@Override
			public int getPriority() {
				return NativeThread.MIN_PRIORITY;
			}
		}, "DiffieHellman exponential signing");
	}

    private void _fillJFKECDHFIFOOffThread() {
        // do it off-thread
        node.executor.execute(new PrioRunnable() {
            @Override
            public void run() {
                _fillJFKECDHFIFO();
            }
            @Override
            public int getPriority() {
                return NativeThread.MIN_PRIORITY;
            }
        }, "ECDH exponential signing");
    }
    
	private void _fillJFKDHFIFO() {
	    synchronized (dhContextFIFO) {
	        int size = dhContextFIFO.size();
	        if((size > 0) && (size + 1 > DH_CONTEXT_BUFFER_SIZE)) {
	            DiffieHellmanLightContext result = null;
	            long oldestSeen = Long.MAX_VALUE;

				for (DiffieHellmanLightContext tmp: dhContextFIFO) {
					if(tmp.lifetime < oldestSeen) {
						oldestSeen = tmp.lifetime;
						result = tmp;
					}
				}
				dhContextFIFO.remove(dhContextToBePrunned = result);
			}

			dhContextFIFO.addLast(_genLightDiffieHellmanContext());
		}
	}
    
	private void _fillJFKECDHFIFO() {
        synchronized (ecdhContextFIFO) {
            int size = ecdhContextFIFO.size();
            if((size > 0) && (size + 1 > DH_CONTEXT_BUFFER_SIZE)) {
                ECDHLightContext result = null;
                long oldestSeen = Long.MAX_VALUE;

                for (ECDHLightContext tmp: ecdhContextFIFO) {
                    if(tmp.lifetime < oldestSeen) {
                        oldestSeen = tmp.lifetime;
                        result = tmp;
                    }
                }
                ecdhContextFIFO.remove(ecdhContextToBePrunned = result);
            }

            ecdhContextFIFO.addLast(_genECDHLightContext());
        }
    }	

	/**
	 * Change the DH Exponents on a regular basis but at most once every 30sec
	 *
	 * @return {@link DiffieHellmanLightContext}
	 */
	private DiffieHellmanLightContext getLightDiffieHellmanContext() {
		final long now = System.currentTimeMillis();
		DiffieHellmanLightContext result = null;

		synchronized (dhContextFIFO) {
			result = dhContextFIFO.pollFirst();

			// Shall we replace one element of the queue ?
			if((jfkDHLastGenerationTimestamp + DH_GENERATION_INTERVAL) < now) {
				jfkDHLastGenerationTimestamp = now;
				_fillJFKDHFIFOOffThread();
			}

            // If we didn't get any, generate on-thread
            if(result == null)
                result = _genLightDiffieHellmanContext();
            
			dhContextFIFO.addLast(result);
		}

		if(logMINOR) Logger.minor(this, "getLightDiffieHellmanContext() is serving "+result.hashCode());
		return result;
	}
	
    /**
     * Change the ECDH key on a regular basis but at most once every 30sec
     *
     * @return {@link DiffieHellmanLightContext}
     * @throws NoContextsException 
     */
    private ECDHLightContext getECDHLightContext() throws NoContextsException {
        final long now = System.currentTimeMillis();
        ECDHLightContext result = null;

        synchronized (ecdhContextFIFO) {
            result = ecdhContextFIFO.pollFirst();
            
            // Shall we replace one element of the queue ?
            if((jfkECDHLastGenerationTimestamp + DH_GENERATION_INTERVAL) < now) {
                jfkECDHLastGenerationTimestamp = now;
                _fillJFKECDHFIFOOffThread();
            }
            
            // Don't generate on-thread as it might block.
            if(result == null)
                throw new NoContextsException();

            ecdhContextFIFO.addLast(result);
        }

        if(logMINOR) Logger.minor(this, "getECDHLightContext() is serving "+result.hashCode());
        return result;
    }
    
    private static class NoContextsException extends Exception {
    	
    	private enum CONTEXT {
    		SENDING,
    		REPLYING
    	};
    }
	

	/**
	 * Used in processJFK[3|4]
	 * That's O^(n) ... but we have only a few elements and
	 * we call it only once a round-trip has been done
	 *
	 * @param exponential
	 * @return the corresponding DiffieHellmanLightContext with the right exponent
	 */
	private DiffieHellmanLightContext findContextByExponential(BigInteger exponential) {
		synchronized (dhContextFIFO) {
			for (DiffieHellmanLightContext result : dhContextFIFO) {
				if(exponential.equals(result.myExponential)) {
					return result;
				}
			}

			if((dhContextToBePrunned != null) && ((dhContextToBePrunned.myExponential).equals(exponential)))
				return dhContextToBePrunned;
		}
		return null;
	}
	
	 /**
     * Used in processJFK[3|4]
     * That's O^(n) ... but we have only a few elements and
     * we call it only once a round-trip has been done
     *
     * @param exponential
     * @return the corresponding ECDHLightContext with the right exponent
     */
    private ECDHLightContext findECDHContextByPubKey(ECPublicKey exponential) {
        synchronized (ecdhContextFIFO) {
            for (ECDHLightContext result : ecdhContextFIFO) {
                if(exponential.equals(result.getPublicKey())) {
                    return result;
                }
            }

            if((ecdhContextToBePrunned != null) && ((ecdhContextToBePrunned.getPublicKey()).equals(exponential)))
                return ecdhContextToBePrunned;
        }
        return null;
    }

	/*
	 * Prepare DH parameters of message2 for them to be signed (useful in message3 to check the sig)
	 */
	private byte[] assembleDHParams(byte[] exponential, DSAGroup group) {
		byte[] _myGroup = group.getP().toByteArray();
		byte[] toSign = new byte[exponential.length + _myGroup.length];

		System.arraycopy(exponential, 0, toSign, 0, exponential.length);
		System.arraycopy(_myGroup, 0, toSign, exponential.length, _myGroup.length);

		return toSign;
	}

	private byte[] assembleDHParams(byte[] nonceInitiator,byte[] nonceResponder,byte[] initiatorExponential, byte[] responderExponential, byte[] id, byte[] sa) {
		byte[] result = new byte[nonceInitiator.length + nonceResponder.length + initiatorExponential.length + responderExponential.length + id.length + sa.length];
		int offset = 0;

		System.arraycopy(nonceInitiator, 0,result,offset,nonceInitiator.length);
		offset += nonceInitiator.length;
		System.arraycopy(nonceResponder,0 ,result,offset,nonceResponder.length);
		offset += nonceResponder.length;
		System.arraycopy(initiatorExponential, 0, result,offset, initiatorExponential.length);
		offset += initiatorExponential.length;
		System.arraycopy(responderExponential, 0, result, offset, responderExponential.length);
		offset += responderExponential.length;
		System.arraycopy(id, 0, result , offset,id.length);
		offset += id.length;
		System.arraycopy(sa, 0, result , offset,sa.length);

		return result;
	}

	private byte[] getTransientKey() {
		synchronized (authenticatorCache) {
			return transientKey;
		}
	}

	private byte[] computeJFKSharedKey(byte[] exponential, byte[] nI, byte[] nR, String what) {
		assert("0".equals(what) || "1".equals(what) || "2".equals(what) || "3".equals(what)
				|| "4".equals(what) || "5".equals(what) || "6".equals(what) || "7".equals(what));
		byte[] number = null;
		try {
			number = what.getBytes("UTF-8");
		} catch (UnsupportedEncodingException e) {
			throw new Error("Impossible: JVM doesn't support UTF-8: " + e, e);
		}

		byte[] toHash = new byte[NONCE_SIZE * 2 + number.length];
		int offset = 0;
		System.arraycopy(nI, 0, toHash, offset, NONCE_SIZE);
		offset += NONCE_SIZE;
		System.arraycopy(nR, 0, toHash, offset, NONCE_SIZE);
		offset += NONCE_SIZE;
		System.arraycopy(number, 0, toHash, offset, number.length);

		return HMAC.macWithSHA256(exponential, toHash, HASH_LENGTH);
	}

	private long timeLastReset = -1;

	/**
	 * How big can the authenticator cache get before we flush it ?
	 * n * 40 bytes (32 for the authenticator and 8 for the timestamp)
	 *
	 * We push to it until we reach the cap where we rekey or we reach the PFS interval
	 */
	private int getAuthenticatorCacheSize() {
		if(crypto.isOpennet && node.wantAnonAuth(true)) // seednodes
			return 5000; // 200kB
		else
			return 250; // 10kB
	}
	
	/**
	 * Change the transient key used by JFK.
	 *
	 * It will determine the PFS interval, hence we call it at least once every 30mins.
	 *
	 * @return True if we reset the transient key and therefore the authenticator cache.
	 */
	private boolean maybeResetTransientKey() {
		long now = System.currentTimeMillis();
		boolean isCacheTooBig = true;
		int authenticatorCacheSize = 0;
		int AUTHENTICATOR_CACHE_SIZE = getAuthenticatorCacheSize();
		synchronized (authenticatorCache) {
			authenticatorCacheSize = authenticatorCache.size();
			if(authenticatorCacheSize < AUTHENTICATOR_CACHE_SIZE) {
				isCacheTooBig = false;
				if(now - timeLastReset < TRANSIENT_KEY_REKEYING_MIN_INTERVAL)
					return false;
			}
			timeLastReset = now;

			node.random.nextBytes(transientKey);

			// reset the authenticator cache
			authenticatorCache.clear();
		}
		node.getTicker().queueTimedJob(transientKeyRekeyer, "JFKmaybeResetTransientKey"+now, TRANSIENT_KEY_REKEYING_MIN_INTERVAL, false, false);
		Logger.normal(this, "JFK's TransientKey has been changed and the message cache flushed because "+(isCacheTooBig ? ("the cache is oversized ("+authenticatorCacheSize+')') : "it's time to rekey")+ " on " + this);
		return true;
	}

	@Override
	public Status getConnectivityStatus() {
		long now = System.currentTimeMillis();
		if (now - lastConnectivityStatusUpdate < 3 * 60 * 1000)
			return lastConnectivityStatus;

		Status value;
		if (crypto.config.alwaysHandshakeAggressively())
			value = AddressTracker.Status.DEFINITELY_NATED;
		else
			value = sock.getDetectedConnectivityStatus();

		lastConnectivityStatusUpdate = now;

		return lastConnectivityStatus = value;
	}

	@Override
	public boolean allowConnection(PeerNode pn, FreenetInetAddress addr) {
		return crypto.allowConnection(pn, addr, sock);
	}

	@Override
	public void setPortForwardingBroken() {
		crypto.setPortForwardingBroken();
	}
	
	/** @returns the modulus length in bytes for a given negType */
	private int getModulusLength(int negType) {
	    if(negType < 8)
	        return DiffieHellman.modulusLengthInBytes();
	    else
	        return ecdhCurveToUse.modulusSize;
	}

}

