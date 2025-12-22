package Kartoffel.Licht.Networking;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;

import Kartoffel.Licht.Networking.Net.ClientPeerI;
import Kartoffel.Licht.Networking.Net.NetBuffer;
import Kartoffel.Licht.Networking.Net.NetCloseException;
import Kartoffel.Licht.Networking.Net.NetConnection;
import Kartoffel.Licht.Networking.Net.NetException;
import Kartoffel.Licht.Networking.Net.OPCODE;
import Kartoffel.Licht.Networking.Net.PrimitiveType;
import Kartoffel.Licht.Networking.Net.ServerPeerI;

//TODO make server also accept HTTP requests (for websockets)
/**
 * Server implementation for the KLNetworking library. (see {@link Net})<br>
 * To create a server, see {@link #NetServer(InetSocketAddress, Class, Class, ThreadFactory)}
 * @param <ServerPeer> the server peer
 * @param <ClientPeer> the client peer
 * @author Kartoffel Licht
 */
public abstract class NetServer<ServerPeer extends ServerPeerI, ClientPeer extends ClientPeerI> implements AutoCloseable{
	
	private Thread mainThread;
	private List<worker> networkingThreads = new ArrayList<>();
	private record worker(Thread thread, Selector selector, AtomicInteger count, ConcurrentLinkedQueue<workerReg> newChannels) {};
	private record workerReg(SocketChannel channel, Object attach, Consumer<SelectionKey> key) {};
	
	private final ConcurrentHashMap<SocketChannel, ServerPeer> speers = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<SocketChannel, ClientPeer> cpeers = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<SocketChannel, Net.NetBuffer> rbuffers = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<SocketChannel, Net.NetBuffer> wbuffers = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<SocketChannel, ByteChannel> ioChannels = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<SocketChannel, worker> workerLookupp = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<SocketChannel, NetConnection<ServerPeer, ClientPeer>> clients = new ConcurrentHashMap<>();
	
	private Method[] speerMethods;
//	private Method[] cpeerMethods;
	private final HashMap<Short, Method> speerMethodsMap = new HashMap<>();
	private final HashMap<Short, PrimitiveType[]> methodParameterMap = new HashMap<>();
	
	private final List<disconnection> pendingDisconnection = new CopyOnWriteArrayList<>();
	private record disconnection(SocketChannel channel, long timeMilli, boolean fatal) {}
	private Selector s;
	private ServerSocketChannel a;
	
	private MessageDigest sha1_digest;
	
	private final Pattern webSocketSecretPattern = Pattern.compile("Sec-WebSocket-Key: (.*)");
	
	private volatile boolean[] enableNormalConnections = {true}, enableWebsocketConnections = {false}, enableHTMLConnections = {false}, enableHTTPConnections = {false};
	private volatile boolean setAsPlainData = false;

	private long validationTimeout = (long) (1000*2); //time to validate before disconnection

	private long minTime; //Min time until the selector should wakeup

	private Runnable serv; //Actual server code.
	
	private int numWorkerThreads;
	
	/**
	 * Creates a new NetServer
	 * @param serverSocketChannel the {@link ServerSocketChannel} to use
	 * @param server_peer the class of the server peer
	 * @param client_peer the class of the client peer
	 * @param factory the thread factory used to create the thread.
	 * @param sslcontext {@link SSLContext} used for encryption. If null, the server won't use encryption
	 * @param numWorkerThreads number of worker threads, if 0 then the work is done on the main thread
	 * @throws IllegalArgumentException if either client_peer or server_peer is not an interface, or any non-ignored method of them fails the {@link Net#check(Method)}
	 * @throws IOException if any IO-exception occurs
	 * @throws IllegalArgumentException if numWorkerThreads is below 0
	 * @throws NullPointerException if any argument is null
	 */
	public NetServer(final ServerSocketChannel serverSocketChannel, final Class<ServerPeer> server_peer, final Class<ClientPeer> client_peer, final ThreadFactory factory, final int numWorkerThreads) throws IOException {
		if(numWorkerThreads < 0)
			throw new IllegalArgumentException("Number of worker threads can't be below 0!");
		Net.init();
		try {
			sha1_digest = MessageDigest.getInstance("SHA-1");
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		}
//		this.cclass_server = server_peer;
//		this.cclass_client = client_peer;
		speerMethods = server_peer.getDeclaredMethods();
//		cpeerMethods = client_peer.getDeclaredMethods();
		PrimitiveType[] ds = null;
		for(int i = 0; i < speerMethods.length; i++) {
			if(Net.check(speerMethods[i])) {
				short id = Net.getUUID(speerMethods[i]);
				if((ds = methodParameterMap.put(id, Net.getParameters(speerMethods[i]))) != null)
					throw new Error("Duplicate method UUID("+id+")! " + ds + " & " + speerMethods[i]);
				speerMethodsMap.put(id, speerMethods[i]);
			}
		}
//		for(int i = 0; i < cpeerMethods.length; i++) {
//			if(Net.check(cpeerMethods[i]))
//				if((ds = methodParameterMap.put((short) Net.getUUID(cpeerMethods[i]), Net.getParameters(cpeerMethods[i]))) != null)
//						throw new Error("Duplicate method UUID("+Net.getUUID(speerMethods[i])+")! " + ds + " & " + speerMethods[i]);
//		}
		this.a = serverSocketChannel;
		s = Selector.open();
		serverSocketChannel.register(s, SelectionKey.OP_ACCEPT, serverSocketChannel);
		this.numWorkerThreads = numWorkerThreads;
		final Consumer<SelectionKey> IOHandler = (key)->{
			if(!(key.attachment() instanceof NetConnection<?, ?> con))
//				throw new IllegalStateException("INVALID ATTACHMENT!"); //Don't throw
				return;
			try {
				synchronized (con.c) {
					try {
						if(key.isReadable()) {
							if(con.plainDataCon != null) {
								loop: //Exposed functionality, similar to which is seen in the NetBuffer class
									while(true) {
										con.plainDataCon.clear();
										int d = con.io.read(con.plainDataCon);
										if(d == -1)
											throw new NetCloseException();
										else if(d == 0)
											break loop;
										con.sp.onPlainData(con.plainDataCon, d);
									}
							} else {
								if(con.isWebSocketConnection)
									con.rb.readFromWebSocket(con.io);
								else
									con.rb.readFrom(con.io);
							}
						} else if(key.isWritable()) {
							con.wb.writeTo(con.io);
						}
					} catch (Exception d) {
						if(!Net.isWouldBlockException.test(d))
							throw d;
						//Do nothing. the readFrom/writeTo methods always throw the WouldBlockException when using TLS
		         }
				}
		
			} catch (NetCloseException|ClosedChannelException a) {  //Normal disconnection
//				System.err.println("DISCONN");
				disconnect(con.c);
			} catch (Exception e) {
//				System.err.println("ERROR");
				onException(con.c, "Serverside client handling exception", e);
				if(con.c != null) {
					NetConnection<ServerPeer, ClientPeer> conn = clients.get(con.c);
					if(conn != null)
						conn.sp.onException(e);
					disconnect(con.c);
				}
			}
		
		};
		final Consumer<SelectionKey> AcceptHandler = (key)->{
			SocketChannel channelo = null;
			try { //Client exception catching.
				if(key.attachment() == serverSocketChannel && key.isAcceptable()) { //Accepting connection
						final var channel = serverSocketChannel.accept();
						synchronized(pendingDisconnection) {
							pendingDisconnection.add(new disconnection(channel, System.currentTimeMillis()+validationTimeout , false));
						}
						s.wakeup();
						channelo = channel;//Used for exceptions
						channel.configureBlocking(false);
						var r = accept(channel);
						if(r == null) { //Channel not accepted, closing
							channel.close();
						} else { //Accepted client
							if(r != channel)
								ioChannels.put(channel, r);
							@SuppressWarnings("unchecked")
							final NetConnection<ServerPeer, ClientPeer>[] netConA = new NetConnection[1];
							final var write_buffer = new NetBuffer(channel) {
								@Override
								public void setReadWrite() {
									super.setReadWrite();
									netConA[0].sp.onWriteBegin();
								}
								@Override
								public void setReadOnly() {
									super.setReadOnly();
									netConA[0].sp.onWriteEnd();
								}
							};
							final var read_buffer = setAsPlainData ? null : new NetBuffer(channel) {
								
								@Override
								public void onHTTPRead(byte[] datab) {
									netConA[0].isWebConnection = true;
									String data = new String(datab, StandardCharsets.UTF_8);
									Matcher match = webSocketSecretPattern.matcher(data);
									if(match.find()) { //Is WebSocket
										if(!enableWebsocketConnections[0])
											throw new NetException("Websocket connections not enabled!");
										netConA[0].isWebSocketConnection = true;
										String re = Base64.getEncoder().encodeToString(sha1_digest.digest((match.group(1) + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.UTF_8)));
										byte[] response = ("HTTP/1.1 101 Switching Protocols\r\n" //TODO optimize (memory)
											    + "Connection: Upgrade\r\n"
											    + "Upgrade: websocket\r\n"
											    + "Sec-WebSocket-Accept: "
											    + re
											    + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
										write_buffer.writeRaw(response); //TODO implement websocket logic + teaVM Client
									} else { //Is normal http/text connection
										if(!enableHTMLConnections[0])
											throw new NetException("HTML connections not enabled!");
										onHTMLConnection(netConA[0], data);
									}
								}
								
								private Object[] args;

								@Override
								public void onRead(byte[] data, byte extra) {
									switch (extra) {
									case Net.TYPE_STRING_MESSAGE: {
										var msg = new String(data);
										onMessage(channel, msg);
										netConA[0].msgHandlr.accept(msg);
										break;
									}
									case Net.TYPE_FUNCTION_INVOKE: {
										try {
											ByteArrayInputStream bais = new ByteArrayInputStream(data);
											DataInputStream dis = new DataInputStream(bais);
											short id = dis.readShort();
											var parameters = methodParameterMap.get(id);
											if(parameters == null)
												throw new NetException("Method not found " + id);
											args = Net.deserializeObjects(dis, parameters, args);
											netConA[0].sph.accept(id, a==null?null:args);
										} catch (IOException e) {
											throw new RuntimeException(e);
										}
										break;
									}
									case Net.TYPE_CUSTOM: {
										netConA[0].customDataHandlr.accept(data);
										onCustomData(channel, data);
										break;
									}
									default:
										throw new IllegalArgumentException("Unexpected value: " + extra);
									}
								}
								@Override
								public void onReadWebSocket(byte[] data, OPCODE opcode) throws IOException {
									if(opcode == OPCODE.PING)
										Net.writeWebSocketPacket(netConA[0].wb, OPCODE.PONG, data);
									else if(opcode == OPCODE.CLOSE)
										Net.writeWebSocketPacket(netConA[0].wb, OPCODE.CLOSE, data);
								}
							};
							if(!setAsPlainData) {
								read_buffer.enableWebConnections = enableHTTPConnections;
								read_buffer.enableNormalConnections = enableNormalConnections;
								rbuffers.put(channel, read_buffer);
							}
							wbuffers.put(channel, write_buffer);
							@SuppressWarnings("unchecked")
							ClientPeer client = (ClientPeer)Proxy.newProxyInstance(client_peer.getClassLoader(), new Class<?>[] {client_peer}, new InvocationHandler() {
								
								@Override
								public Object invoke(Object proxy, Method method, Object[] args) throws Exception {
									short id = (short) Net.getUUID(method);
									if(id != -1) {
										if(!channel.isConnected())
											return null;
										netConA[0].cph.accept(id, args);
										return null; //Return Future
									}
									else if(method.equals(Net.M_GETCONNECTION)) return netConA[0];
									else if(method.equals(Net.M_EQUALS)){return this.equals(args[0]);}
									else if(method.equals(Net.M_HASHCODE)) {return this.hashCode();}
									else if(method.equals(Net.M_TOSTRING)) {return "{"+channel+"}";}
									throw new IllegalAccessException("Not available! ("+method+")");
								}
							});
							final var speer = accept(client);
							final NetConnection<ServerPeer, ClientPeer> netCon = new NetConnection<ServerPeer, ClientPeer>(read_buffer, write_buffer, channel, speer, client, r);
							if(setAsPlainData)
								netCon.plainDataCon = ByteBuffer.allocateDirect(Net.PLAIN_DATA_BUFFER_SIZE);
							if(numWorkerThreads != 0){
								worker w = poll();
								w.newChannels().add(new workerReg(channel, netCon, (a)->write_buffer.writeSelectionKey = a));
								w.selector.wakeup();
								workerLookupp.put(channel, w);
							} else {
								write_buffer.writeSelectionKey = channel.register(s, SelectionKey.OP_READ, netCon);
							}
							netConA[0] = netCon;
							netCon.ns = this;
							netCon.cph = (a, b)->{try {if(netCon.isWebSocketConnection)Net.writeCallWebSocket(write_buffer, a, b);else Net.writeCall(write_buffer, a, b);} catch (IOException e) {throw new RuntimeException(e);}};
							netCon.sph = (a, b)->{try {speerMethodsMap.get(a).invoke(speer, b);} catch (IllegalAccessException | InvocationTargetException e) {throw new RuntimeException(e);}};
//							write_buffer.writeSelectionKey = skey; //Dont forget
							speer.onConnect(channel, netCon);
							speers.put(channel, speer);
							cpeers.put(channel, client);
							clients.put(channelo, netCon);
						}
				} else if(numWorkerThreads == 0){
					IOHandler.accept(key); //Run worker on main
				}
			}catch (NetCloseException|ClosedChannelException a) {  //Normal disconnection
				if(channelo != null)
					disconnect(channelo);
			} catch (Exception e) {
				onException(channelo, "Serverside client handling exception", e);
				if(channelo != null) {
					NetConnection<ServerPeer, ClientPeer> conn = clients.get(channelo);
					if(conn != null)
						conn.sp.onException(e);
					disconnect(channelo);
				}
			}
			
		};
		
		serv = ()->{
			while(isOpen()) {
				minTime = Long.MAX_VALUE;
				onServerBeat(); //Might be useful
				try { //This try catch block will not actually catch anything.
					if(pendingDisconnection.size() > 0)
						synchronized (pendingDisconnection) {
							pendingDisconnection.removeIf((cc)->{
								if(!isConnected(cc.channel)) //Remove if already disconnected
									return true;
								if(cc.fatal) {
									if(System.currentTimeMillis() > cc.timeMilli) { //!wbuffers.get(cc.channel).hasDataToSend() //Remove "has data to send" check,instead send a death packet!
										disconnect(cc.channel); //Disconnect because of End of Life
										return true;
									}
								} else {
									if(clients.get(cc.channel).valid) //used for initial connection validation timeout
										return true; //Cancel pending disconnection
									else if(System.currentTimeMillis() >= cc.timeMilli) {
//										System.err.println("TIMOUTED " + cc + " " + clients.get(cc.channel).valid);
										disconnect(cc.channel); //Disconnect because of timeout
										return true;
									}
								}
								minTime = Math.min(minTime, cc.timeMilli);
								return false;
							});
						}
					s.select(AcceptHandler, Math.max(minTime-System.currentTimeMillis(), 1));
				} catch (IOException e) { //Server exception
					try {close();} catch (Exception e2) {throw new RuntimeException(e2);}
					throw new RuntimeException(e);
				}
			}
			try {close();} catch (Exception e) {throw new RuntimeException(e);}
		};
		mainThread = factory.newThread(serv);
		mainThread.setName("NetConnection Server Main Thread @ " + serverSocketChannel.getLocalAddress());
		for(int i = 0; i < numWorkerThreads; i++){
			final Selector ws = Selector.open();
			worker[] wA = new worker[1];
			Runnable workr = ()->{
				try {
					while(isOpen()) {
						while(wA[0].newChannels.size()>0) {
							var d = wA[0].newChannels.poll();
							d.key.accept(d.channel.register(ws, SelectionKey.OP_READ, d.attach)); //Assign to worker)
							wA[0].count.incrementAndGet();
						}
//						System.err.println("asd");
//						ws.select((a)->{
////							System.out.println(a.attachment());
//							asddd.put(a.attachment(), "");
////							System.out.println(asddd.size());
//						});
						ws.select(IOHandler);
					}
				} catch (IOException e) {
					System.err.println("FATAL EXXCEPTION");
					throw new RuntimeException(e);
					//Should never land here
//					e.printStackTrace();
				}
				
			};
			var thr = factory.newThread(workr);
			thr.setName("NetConnection Server Thread #"+i+" @ " + serverSocketChannel.getLocalAddress());
			wA[0] = new worker(thr, ws, new AtomicInteger(), new ConcurrentLinkedQueue<>());
			networkingThreads.add(wA[0]);
		}
	}
//	static ConcurrentHashMap<Object, Object> asddd = new ConcurrentHashMap<>();
//	static volatile int[] asd = new int[55];
	final private worker poll() {
		int d = Integer.MAX_VALUE;
		worker r = null;
		for(var a : networkingThreads) {
			if(a.count.get() < d) {
				d = a.count.get();
				r = a;
			}
		}
		return r;
	}
	
	final void remove(SocketChannel a) {
		synchronized (a) {
			if(!clients.containsKey(a))
				return;
			var c = clients.remove(a);
			if(c.plainDataCon == null) //Check if there even is a read buffer
				rbuffers.remove(a).open = false;
			wbuffers.remove(a).open = false;
			speers.remove(a).onDisconnect();
			cpeers.remove(a);
			if(numWorkerThreads != 0)
				workerLookupp.remove(a).count.decrementAndGet();
			ioChannels.remove(a);
			onRemove(c);
		}
	}
	/**
	 * Validates this connection, eg. it won't be disconnected by the inbuilt timeout system.
	 * @param connection
	 */
	final public void validate(SocketChannel connection) {
		clients.get(connection).valid = true;
	}
	/**
	 * Removes the client from the server and closes the connection.
	 * @param connection the connection
	 */
	public final void disconnect(SocketChannel connection) {
		if(!clients.containsKey(connection))
			return;
		remove(connection);
		try {
			var sel = numWorkerThreads == 0 ? s : workerLookupp.get(connection).selector;
			connection.keyFor(sel).cancel();
			connection.configureBlocking(true); //Shutdown in blocking mode
			connection.shutdownOutput();
			connection.shutdownInput();
			connection.close();
			
		} catch (IOException b) {}
	}
	/**
	 * Removes the client from the server and closes the connection as soon as it has finished writing everything it needs to.
	 * @param connection the connection
	 * @param timeout the timeout in milliseconds. If this timeout is reached the client will be disconnected regardless of what and how much data has been send
	 */
	public final void disconnectLater(SocketChannel connection, int timeout) {
		if(!clients.containsKey(connection))
			return;
		synchronized (pendingDisconnection) {
			var nw = new disconnection(connection, System.currentTimeMillis() + timeout, true);
			for (int i = 0; i < pendingDisconnection.size(); i++) {
				if (pendingDisconnection.get(i).channel == connection) {
					pendingDisconnection.set(i, nw);
					nw = null;
				}
			}
			if (nw != null)
				pendingDisconnection.add(nw);
		}
		s.wakeup();
	}
	/**
	 * Writes an deaths packet to the connection. When this packet is reached, the connection closes.
	 * @param connection
	 */
	public final void disconnectOnWriteEnd(SocketChannel connection) {
		if(!clients.containsKey(connection))
			return;
		wbuffers.get(connection).writeDeath();
	}
	/**
	 * Called when a client tries to connect. This is run before any implemented handshaking occurs
	 * @param channel the channel
	 * @return if the channel should be accepted
	 */
	protected ByteChannel accept(SocketChannel channel) {return channel;};
	/**
	 * Called when a client was allowed to connect, and it's connection is being set up.
	 * @param clientpeer the client peer
	 * @return the server peer that will be invoked by the client
	 */
	protected abstract ServerPeer accept(ClientPeer clientpeer);
	/**
	 * Called when a client is removed.
	 * @param connection the connection
	 */
	protected void onRemove(NetConnection<ServerPeer, ClientPeer> connection) {
		
	}
	
	protected void onServerBeat() {
		
	}
	/**
	 * Called when a web client without webSocket-capabilities connects (eg. a Browser)
	 * @param connection the connection
	 */
	protected void onHTMLConnection(NetConnection<ServerPeer, ClientPeer> connection, String header) {
		connection.wb.writeRaw(("HTTP/1.1 200 OKE\nConnection: close\n\r\n\r").getBytes(StandardCharsets.UTF_8));
		connection.wb.writeRaw("<html>Nothing to be seen here</html>".getBytes(StandardCharsets.UTF_8));
		validate(connection.c); //Remove default timeout
		disconnectLater(connection.c, 1000); //Add custom one.
	}
	/**
	 * Called when a message is received by a client.
	 * @param channel the channel
	 * @param msg the message
	 */
	protected void onMessage(SocketChannel channel, String msg) {
		if(Net.DEBUG_MODE)
		System.out.println("[Server]: Message from client ("+channel+"): " + msg);
	}
	/**
	 * Called when custom data is delivered.
	 * @param channel the channel
	 * @param data the data
	 */
	protected void onCustomData(SocketChannel channel, byte[] data) {
		
	}
	/**
	 * Called when an exception occurs. The client generally will be automatically disconnected in conjunction to this call
	 * @param channel the channel
	 * @param msg the message
	 * @param exception the exception
	 */
	protected void onException(SocketChannel channel, String msg, Exception exception) {
		if(Net.DEBUG_MODE) {
			System.err.println("[Server]: " + channel + " " + msg + " " + exception);
			exception.printStackTrace();
		}
	}
	/**
	 * Starts the server thread.
	 */
	public final void start() {
		mainThread.start();
		networkingThreads.forEach((b)->{
			b.thread.start();
		});
	}
	/**
	 * Disconnects all clients and closes this server.
	 */
	@Override
	public void close() throws Exception {
		getClientConnections().asIterator().forEachRemaining((a)->{
			disconnect(a);
		});
		a.close();
		s.wakeup();
	}
	/**
	 * 
	 * {@return if this server is open}
	 */
	public boolean isOpen() {
		return a.isOpen();
	}
	/**
	 * {@return all currently connected clients}
	 */
	public final Enumeration<SocketChannel> getClientConnections() {
		return speers.keys();
	}
	/**
	 * {@return the number of all currently connected clients}
	 */
	public final int getClientConnectionCount() {
		return speers.size();
	}
	public final boolean isConnected(SocketChannel c) {
		return clients.containsKey(c);
	}
	public final Collection<NetConnection<ServerPeer, ClientPeer>> getClients() {
		return clients.values();
	}
	/**
	 * {@return the internal server}
	 */
	public final ServerSocketChannel getServerSocketChannel() {
		return a;
	}
	/**
	 * 
	 * {@return the main server thread}
	 */
	public Thread getMainThread() {
		return mainThread;
	}
	/**
	 * {@return a list of workers, may be empty}
	 */
	public List<worker> getNetworkingThreads() {
		return networkingThreads;
	}
	/**
	 * {@return the number of worker threads. may be 0}
	 */
	public int getNumWorkerThreads() {
		return numWorkerThreads;
	}
	/**
	 * Enables HTML connections (websites, etc..). HTTP connections have to be enabled.
	 * @param enableHTMLConnections
	 */
	public final void setHTMLConnectionsEnabled(boolean enableHTMLConnections) {
		this.enableHTMLConnections[0] = enableHTMLConnections;
		setHTTPConnectionsEnabled(this.enableHTMLConnections[0]||this.enableWebsocketConnections[0]);
	}
	/**
	 * Enables HTTP connections. Must be enabled for WebSocket and HTML connections to work.
	 * @param enableHTTPConnections
	 */
	protected final void setHTTPConnectionsEnabled(boolean enableHTTPConnections) {
		this.enableHTTPConnections[0] = enableHTTPConnections;
	}
	/**
	 * Enables normal (TCP) connections.
	 * @param enableNormalConnections
	 */
	public final void setNormalConnectionsEnabled(boolean enableNormalConnections) {
		this.enableNormalConnections[0] = enableNormalConnections;
	}
	/**
	 * If set, further connections will redirect incoming data directly and won't process it.
	 * @param setAsPlainData
	 */
	public void setPlainData(boolean setAsPlainData) {
		this.setAsPlainData = setAsPlainData;
	}
	/**
	 * Enables WebSocket connections. HTTP connections have to be enabled.
	 * @param enableWebsocketConnections
	 */
	public final void setWebsocketConnectionsEnabled(boolean enableWebsocketConnections) {
		this.enableWebsocketConnections[0] = enableWebsocketConnections;
		setHTTPConnectionsEnabled(this.enableHTMLConnections[0]||this.enableWebsocketConnections[0]);
	}
	public void setValidationTimeout(long validationTimeout) {
		this.validationTimeout = validationTimeout;
	}
	public long getValidationTimeout() {
		return validationTimeout;
	}
	/**
	 * {@return if further connections will redirect plain data directly instead of processing it first}
	 */
	public boolean isSetAsPlainData() {
		return setAsPlainData;
	}
//	/**
//	 * {@return A map containing all server-side methods that are invokable, the key being the uuid}
//	 */
//	public HashMap<Short, Method> getSpeerMethodsMap() {
//		return speerMethodsMap;
//	}
	
}
