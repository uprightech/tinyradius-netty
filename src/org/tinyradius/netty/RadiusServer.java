/**
 * $Id: RadiusServer.java,v 1.11 2008/04/24 05:22:50 wuttke Exp $
 * Created on 09.04.2005
 * @author Matthias Wuttke
 * @version $Revision: 1.11 $
 */
package org.tinyradius.netty;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.*;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.Timer;
import io.netty.util.concurrent.*;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.tinyradius.attribute.RadiusAttribute;
import org.tinyradius.dictionary.DefaultDictionary;
import org.tinyradius.dictionary.Dictionary;
import org.tinyradius.packet.AccessRequest;
import org.tinyradius.packet.AccountingRequest;
import org.tinyradius.packet.RadiusPacket;
import org.tinyradius.util.RadiusException;

/**
 * Implements a simple Radius server. This class must be subclassed to
 * provide an implementation for getSharedSecret() and getUserPassword().
 * If the server supports accounting, it must override
 * accountingRequestReceived().
 */
public abstract class RadiusServer<T extends DatagramChannel> {

	private InetAddress listenAddress = null;
	private int authPort = 1812;
	private int acctPort = 1813;
	private T authSocket = null;
	private T acctSocket = null;
	private int socketTimeout = 3000;
	private List receivedPackets = new LinkedList();
	private long duplicateInterval = 30000; // 30 s
	private boolean closing = false;
	private static Log logger = LogFactory.getLog(RadiusServer.class);

	private ChannelFactory<T> factory;
	private EventLoopGroup eventGroup;
	private Dictionary dictionary;
	private Timer timer;

	/**
	 *
	 * @param factory
	 * @param timer
	 */
	public RadiusServer(ChannelFactory<T> factory, Timer timer) {
		this(DefaultDictionary.getDefaultDictionary(), factory, timer);
	}

	/**
	 *
	 * @param dictionary
	 * @param factory
	 * @param timer
	 */
	public RadiusServer(Dictionary dictionary, ChannelFactory<T> factory, Timer timer) {
		if (dictionary == null)
			throw new NullPointerException("dictionary cannot be null");
		if (factory == null)
			throw new NullPointerException("factory cannot be null");
		if (timer == null)
			throw new NullPointerException("timer cannot be null");
		this.dictionary = dictionary;
		this.factory = factory;
		this.timer = timer;
	}

	/**
	 * Returns the shared secret used to communicate with the client with the
	 * passed IP address or null if the client is not allowed at this server.
	 * @param client IP address and port number of client
	 * @return shared secret or null
	 */
	public abstract String getSharedSecret(InetSocketAddress client);

	/**
	 * Returns the password of the passed user. Either this
	 * method or accessRequestReceived() should be overriden.
	 * @param userName user name
	 * @return plain-text password or null if user unknown
	 */
	public abstract String getUserPassword(String userName);

	/**
	 * Constructs an answer for an Access-Request packet. Either this
	 * method or isUserAuthenticated should be overriden.
	 * @param accessRequest Radius request packet
	 * @param client address of Radius client
	 * @return response packet or null if no packet shall be sent
	 * @exception RadiusException malformed request packet; if this
	 * exception is thrown, no answer will be sent
	 */
	public RadiusPacket accessRequestReceived(AccessRequest accessRequest, InetSocketAddress client)
			throws RadiusException {
		String plaintext = getUserPassword(accessRequest.getUserName());
		int type = RadiusPacket.ACCESS_REJECT;
		if (plaintext != null && accessRequest.verifyPassword(plaintext))
			type = RadiusPacket.ACCESS_ACCEPT;

		RadiusPacket answer = new RadiusPacket(type, accessRequest.getPacketIdentifier());
		copyProxyState(accessRequest, answer);
		return answer;
	}

	/**
	 * Constructs an answer for an Accounting-Request packet. This method
	 * should be overriden if accounting is supported.
	 * @param accountingRequest Radius request packet
	 * @param client address of Radius client
	 * @return response packet or null if no packet shall be sent
	 * @exception RadiusException malformed request packet; if this
	 * exception is thrown, no answer will be sent
	 */
	public RadiusPacket accountingRequestReceived(AccountingRequest accountingRequest, InetSocketAddress client)
			throws RadiusException {
		RadiusPacket answer = new RadiusPacket(RadiusPacket.ACCOUNTING_RESPONSE, accountingRequest.getPacketIdentifier());
		copyProxyState(accountingRequest, answer);
		return answer;
	}

	/**
	 * Starts the Radius server.
	 * @param eventGroup
	 * @param listenAuth open auth port?
	 * @param listenAcct open acct port?
	 */
	public Future<RadiusServer<T>> start(EventLoopGroup eventGroup, boolean listenAuth, boolean listenAcct) {

		if (this.eventGroup != null)
			return new DefaultPromise<RadiusServer<T>>(GlobalEventExecutor.INSTANCE)
					.setFailure(new IllegalStateException("Server already started"));

		this.eventGroup = eventGroup;

		final Promise<RadiusServer<T>> promise =
				new DefaultPromise<RadiusServer<T>>(eventGroup.next());

		listenAuth().addListener(new ChannelFutureListener() {
			public void operationComplete(ChannelFuture future) throws Exception {
				if (!future.isSuccess()) {
					promise.setFailure(future.cause());
				} else {
					listenAcct().addListener(new ChannelFutureListener() {
						public void operationComplete(ChannelFuture future) throws Exception {
							if (future.isSuccess()) {
								promise.setSuccess(RadiusServer.this);
							} else {
								promise.setFailure(future.cause());
							}
						}
					});
				}
			}
		});

		return promise;
	}

	/**
	 * Stops the server and closes the sockets.
	 */
	public void stop() {
		logger.info("stopping Radius server");
		closing = true;
		if (authSocket != null)
			authSocket.close();
		if (acctSocket != null)
			acctSocket.close();
	}

	/**
	 * Returns the auth port the server will listen on.
	 * @return auth port
	 */
	public int getAuthPort() {
		return authPort;
	}

	/**
	 * Sets the auth port the server will listen on.
	 * @param authPort auth port, 1-65535
	 */
	public void setAuthPort(int authPort) {
		if (authPort < 1 || authPort > 65535)
			throw new IllegalArgumentException("bad port number");
		this.authPort = authPort;
		this.authSocket = null;
	}

	/**
	 * Sets the acct port the server will listen on.
	 * @param acctPort acct port 1-65535
	 */
	public void setAcctPort(int acctPort) {
		if (acctPort < 1 || acctPort > 65535)
			throw new IllegalArgumentException("bad port number");
		this.acctPort = acctPort;
		this.acctSocket = null;
	}

	/**
	 * Returns the acct port the server will listen on.
	 * @return acct port
	 */
	public int getAcctPort() {
		return acctPort;
	}

	/**
	 * Returns the duplicate interval in ms.
	 * A packet is discarded as a duplicate if in the duplicate interval
	 * there was another packet with the same identifier originating from the
	 * same address.
	 * @return duplicate interval (ms)
	 */
	public long getDuplicateInterval() {
		return duplicateInterval;
	}

	/**
	 * Sets the duplicate interval in ms.
	 * A packet is discarded as a duplicate if in the duplicate interval
	 * there was another packet with the same identifier originating from the
	 * same address.
	 * @param duplicateInterval duplicate interval (ms), >0
	 */
	public void setDuplicateInterval(long duplicateInterval) {
		if (duplicateInterval <= 0)
			throw new IllegalArgumentException("duplicate interval must be positive");
		this.duplicateInterval = duplicateInterval;
	}

	/**
	 * Returns the IP address the server listens on.
	 * Returns null if listening on the wildcard address.
	 * @return listen address or null
	 */
	public InetAddress getListenAddress() {
		return listenAddress;
	}

	/**
	 * Sets the address the server listens on.
	 * Must be called before start().
	 * Defaults to null, meaning listen on every
	 * local address (wildcard address).
	 * @param listenAddress listen address or null
	 */
	public void setListenAddress(InetAddress listenAddress) {
		this.listenAddress = listenAddress;
	}

	/**
	 * Copies all Proxy-State attributes from the request
	 * packet to the response packet.
	 * @param request request packet
	 * @param answer response packet
	 */
	protected void copyProxyState(RadiusPacket request, RadiusPacket answer) {
		List proxyStateAttrs = request.getAttributes(33);
		for (Iterator i = proxyStateAttrs.iterator(); i.hasNext();) {
			RadiusAttribute proxyStateAttr = (RadiusAttribute)i.next();
			answer.addAttribute(proxyStateAttr);
		}
	}

	/**
	 * Listens on the auth port (blocks the current thread).
	 * Returns when stop() is called.
	 * @return ChannelFuture
	 *
	 */
	protected ChannelFuture listenAuth() {
		logger.info("starting RadiusAuthListener on port " + getAuthPort());
		return listen(getAuthSocket(), new InetSocketAddress(getListenAddress(), getAuthPort()));
	}

	/**
	 * Listens on the acct port (blocks the current thread).
	 * Returns when stop() is called.
	 * @return ChannelFuture
	 */
	protected ChannelFuture listenAcct() {
		logger.info("starting RadiusAcctListener on port " + getAcctPort());
		return listen(getAcctSocket(), new InetSocketAddress(getListenAddress(), getAcctPort()));
	}

	/**
	 * Listens on the passed socket, blocks until stop() is called.
	 * @param channel to listen on
	 * @param listenAddress the address to bind to
	 */
	protected ChannelFuture listen(final T channel, InetSocketAddress listenAddress) {
		if (channel == null)
			throw new NullPointerException("channel cannot be null");
		if (listenAddress == null)
			throw new NullPointerException("listenAddress cannot be null");

		ChannelFuture future = channel.bind(listenAddress);
		future.addListeners(new ChannelFutureListener() {
			public void operationComplete(ChannelFuture channelFuture) throws Exception {
				channel.pipeline().addLast(new RadiusChannelHandler());
			}
		});

		return future;
	}

	/**
	 * Handles the received Radius packet and constructs a response.
	 * @param localAddress local address the packet was received on
	 * @param remoteAddress remote address the packet was sent by
	 * @param request the packet
	 * @return response packet or null for no response
	 * @throws RadiusException
	 */
	protected RadiusPacket handlePacket(InetSocketAddress localAddress, InetSocketAddress remoteAddress, RadiusPacket request, String sharedSecret)
			throws RadiusException, IOException {
		RadiusPacket response = null;

		// check for duplicates
		if (!isPacketDuplicate(request, remoteAddress)) {
			if (localAddress.getPort() == getAuthPort()) {
				// handle packets on auth port
				if (request instanceof AccessRequest)
					response = accessRequestReceived((AccessRequest)request, remoteAddress);
				else
					logger.error("unknown Radius packet type: " + request.getPacketType());
			} else if (localAddress.getPort() == getAcctPort()) {
				// handle packets on acct port
				if (request instanceof AccountingRequest)
					response = accountingRequestReceived((AccountingRequest)request, remoteAddress);
				else
					logger.error("unknown Radius packet type: " + request.getPacketType());
			} else {
				// ignore packet on unknown port
			}
		} else
			logger.info("ignore duplicate packet");

		return response;
	}

	/**
	 *
	 * @return
	 */
	protected ChannelFactory<T> factory() {
		return factory;
	}

	/**
	 * Returns a socket bound to the auth port.
	 * @return socket
	 * @throws SocketException
	 */
	protected T getAuthSocket() {
		if (authSocket == null) {
			authSocket = factory.newChannel();
		}
		return authSocket;
	}

	/**
	 * Returns a socket bound to the acct port.
	 * @return socket
	 * @throws SocketException
	 */
	protected T getAcctSocket() {
		if (acctSocket == null) {
			acctSocket = factory.newChannel();
		}
		return acctSocket;
	}

	/**
	 * Creates a Radius response datagram packet from a RadiusPacket to be send.
	 * @param packet RadiusPacket
	 * @param secret shared secret to encode packet
	 * @param address where to send the packet
	 * @param request request packet
	 * @return new datagram packet
	 * @throws IOException
	 */
	protected DatagramPacket makeDatagramPacket(RadiusPacket packet, String secret, InetSocketAddress address,
												RadiusPacket request)
			throws IOException {

		ByteBuf buf = Unpooled.buffer(RadiusPacket.MAX_PACKET_LENGTH,
				RadiusPacket.MAX_PACKET_LENGTH);

		ByteBufOutputStream bos = new ByteBufOutputStream(buf);
		packet.encodeResponsePacket(bos, secret, request);

		return new DatagramPacket(buf, address);
	}

	/**
	 * Creates a RadiusPacket for a Radius request from a received
	 * datagram packet.
	 * @param packet received datagram
	 * @return RadiusPacket object
	 * @exception RadiusException malformed packet
	 * @exception IOException communication error (after getRetryCount()
	 * retries)
	 */
	protected RadiusPacket makeRadiusPacket(DatagramPacket packet, String sharedSecret)
			throws IOException, RadiusException {
		ByteBufInputStream in = new ByteBufInputStream(packet.content());
		return RadiusPacket.decodeRequestPacket(in, sharedSecret);
	}

	/**
	 * Checks whether the passed packet is a duplicate.
	 * A packet is duplicate if another packet with the same identifier
	 * has been sent from the same host in the last time.
	 * @param packet packet in question
	 * @param address client address
	 * @return true if it is duplicate
	 */
	protected boolean isPacketDuplicate(RadiusPacket packet, InetSocketAddress address) {
		long now = System.currentTimeMillis();
		long intervalStart = now - getDuplicateInterval();

		byte[] authenticator = packet.getAuthenticator();

		synchronized(receivedPackets) {
			for (Iterator i = receivedPackets.iterator(); i.hasNext();) {
				ReceivedPacket p = (ReceivedPacket)i.next();
				if (p.receiveTime < intervalStart) {
					// packet is older than duplicate interval
					i.remove();
				} else {
					if (p.address.equals(address) && p.packetIdentifier == packet.getPacketIdentifier()) {
						if (authenticator != null && p.authenticator != null) {
							// packet is duplicate if stored authenticator is equal
							// to the packet authenticator
							return Arrays.equals(p.authenticator, authenticator);
						} else {
							// should not happen, packet is duplicate
							return true;
						}
					}
				}
			}

			// add packet to receive list
			ReceivedPacket rp = new ReceivedPacket();
			rp.address = address;
			rp.packetIdentifier = packet.getPacketIdentifier();
			rp.receiveTime = now;
			rp.authenticator = authenticator;
			receivedPackets.add(rp);
		}

		return false;
	}

	class RadiusChannelHandler extends SimpleChannelInboundHandler<DatagramPacket> {
		@Override
		public void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
			try {
				// check client
				InetSocketAddress localAddress = packet.recipient();
				InetSocketAddress remoteAddress = packet.sender();

				String secret = getSharedSecret(remoteAddress);
				if (secret == null) {
					if (logger.isInfoEnabled())
						logger.info("ignoring packet from unknown client " + remoteAddress + " received on local address " + localAddress);
					return;
				}

				// parse packet
				RadiusPacket request = makeRadiusPacket(packet, secret);
				if (logger.isInfoEnabled())
					logger.info("received packet from " + remoteAddress + " on local address " + localAddress + ": " + request);

				// handle packet
				logger.trace("about to call RadiusServer.handlePacket()");
				RadiusPacket response = handlePacket(localAddress, remoteAddress, request, secret);

				// send response
				if (response != null) {
					if (logger.isInfoEnabled())
						logger.info("send response: " + response);
					DatagramPacket packetOut = makeDatagramPacket(response, secret, remoteAddress, request);
					ctx.write(packetOut);
				} else {
					logger.info("no response sent");
				}

			} catch (IOException ioe) {
				// error while reading/writing socket
				logger.error("communication error", ioe);
			} catch (RadiusException re) {
				// malformed packet
				logger.error("malformed Radius packet", re);
			}
		}

		@Override
		public void channelReadComplete(ChannelHandlerContext ctx) {
			ctx.flush();
		}
	}

	/**
	 * This internal class represents a packet that has been received by
	 * the server.
	 */
	class ReceivedPacket {

		/**
		 * The identifier of the packet.
		 */
		public int packetIdentifier;

		/**
		 * The time the packet was received.
		 */
		public long receiveTime;

		/**
		 * The address of the host who sent the packet.
		 */
		public InetSocketAddress address;

		/**
		 * Authenticator of the received packet.
		 */
		public byte[] authenticator;

	}
}