 package Kartoffel.Licht.Networking;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.NotYetConnectedException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadFactory;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;


//
// Copyright 2025 Kareem Athamneh
//
// Permission is hereby granted, free of charge, to any person obtaining a copy of this software
// and associated documentation files (the “Software”), to deal in the Software without restriction,
// including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense,
// and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so,
// subject to the following conditions:
// 
// The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
// 
// THE SOFTWARE IS PROVIDED “AS IS”, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
// INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS
// OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
// WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
/**
 * Base class of the KLNetworking library, usable for creating clients. (see {@link NetServer}).<br>
 * To create a client, see {@link Net#connect(InetSocketAddress, ClientPeerI, Class, Class)}.
 * 
 * @author Kartoffel Licht<br>
 * 
 */
public class Net {
	
	Net() {
		
	}
	static boolean init = false;
	public static void init() {
		if(init)
			return;
		init = true;
		M_HASHCODE = get(Object.class, "hashCode");
		M_TOSTRING = get(Object.class, "toString");
		M_EQUALS = get(Object.class, "equals", Object.class);
		M_GETCONNECTION = get(PeerI.class, "getConnection");
	}
	public static Predicate<Exception> isWouldBlockException = (_)->false;
	
	/**
	 * If set to true, the default handlers will print messages to the console.
	 */
	public static boolean DEBUG_MODE = true;
	/**
	 * The version of the library.
	 */
	public static final short VERSION = 1;
	/**
	 * The packet header size (bytes)
	 */
	public static final int HEADER_SIZE = 4;
	/**
	 * Various packet types.
	 */
	public static final byte TYPE_STRING_MESSAGE = 0,
							RESERVED_1= 1,
							TYPE_FUNCTION_INVOKE = 2,
							TYPE_CUSTOM = 3;
	
	/**
	 * The buffer size for plain data routing. Intended for small messages.
	 */
	public static int PLAIN_DATA_BUFFER_SIZE = 256;
	
	public static int MAX_HTTP_HEADER_SIZE = 1024;
	
	public static enum OPCODE {
		CONTINUATION(0),
		TEXT(1),
		BINARY(2),
		PING(0x9),
		PONG(0xa),
		CLOSE(0x8),
		UNKNOWN(-1);
		private byte c;
		OPCODE(int i) {c = (byte) i;}
		public int getValue() {
			return c;
		}
		public static OPCODE get(int n) {
			return switch (n) {
			case 0->CONTINUATION;
			case 1->TEXT;
			case 2->BINARY;
			case 0x9->PING;
			case 0xa->PONG;
			case 0x8->CLOSE;
			default->UNKNOWN;
			};
		}
		
	}
	
	public static enum PrimitiveType {
	    INT, LONG, DOUBLE, BOOLEAN, BYTE, CHAR, SHORT, FLOAT,
	    INT_ARRAY, LONG_ARRAY, DOUBLE_ARRAY, BOOLEAN_ARRAY,
	    BYTE_ARRAY, CHAR_ARRAY, SHORT_ARRAY, FLOAT_ARRAY,
	    UNKNOWN
	}
	static final Map<Class<?>, PrimitiveType> TYPE_MAP = Map.ofEntries(
		    Map.entry(int.class,     PrimitiveType.INT),
		    Map.entry(long.class,    PrimitiveType.LONG),
		    Map.entry(double.class,  PrimitiveType.DOUBLE),
		    Map.entry(boolean.class, PrimitiveType.BOOLEAN),
		    Map.entry(byte.class,    PrimitiveType.BYTE),
		    Map.entry(char.class,    PrimitiveType.CHAR),
		    Map.entry(short.class,   PrimitiveType.SHORT),
		    Map.entry(float.class,   PrimitiveType.FLOAT),

		    Map.entry(int[].class,     PrimitiveType.INT_ARRAY),
		    Map.entry(long[].class,    PrimitiveType.LONG_ARRAY),
		    Map.entry(double[].class,  PrimitiveType.DOUBLE_ARRAY),
		    Map.entry(boolean[].class, PrimitiveType.BOOLEAN_ARRAY),
		    Map.entry(byte[].class,    PrimitiveType.BYTE_ARRAY),
		    Map.entry(char[].class,    PrimitiveType.CHAR_ARRAY),
		    Map.entry(short[].class,   PrimitiveType.SHORT_ARRAY),
		    Map.entry(float[].class,   PrimitiveType.FLOAT_ARRAY)
		);
	
	static Method M_HASHCODE;
	static Method M_TOSTRING;
	static Method M_EQUALS;
	static Method M_GETCONNECTION;
	private static Method get(Class<?> c, String s, Class<?>...parns) {
		try {return c.getDeclaredMethod(s, parns);} catch (NoSuchMethodException e) {throw new RuntimeException(e);}
	}
	/**
	 * An NetException is thrown if there was an exception with the connection (packet parsing, etc..). Generally, the client will also be
	 *  automatically disconnected from the server if such is thrown.
	 */
	public static class NetException extends RuntimeException {
		private static final long serialVersionUID = 8069074475905913430L;
		public NetException(String msg) {
			super(msg);
		}
	}
	/**
	 * Exception thrown when a connection closes. Never exposed.
	 */
	public static class NetCloseException extends RuntimeException {
		private static final long serialVersionUID = 8069074475905913430L;
	}
	/**
	 * Exception thrown when too much data is received from a connection, or if the connection did timeout.
	 */
	public static class NetTooMuchException extends NetException {
		private static final long serialVersionUID = 736305283720525164L;
		public NetTooMuchException(String msg) {
			super(msg);
		}
	}
	@FunctionalInterface
	private static interface ThrowingConsumer<T> {
		public void accept(T t) throws Exception;
	}
	
	public static class NetConnection<ServerPeer extends ServerPeerI, ClientPeer extends ClientPeerI> implements AutoCloseable{
		/**
		 * rb: {@link NetBuffer} used for reading<br>
		 * wb: {@link NetBuffer} used for writing
		 */
		public final NetBuffer rb, wb;
		/**
		 * The input/output of this connection. May be the same object as {@link #c}, or not (eg. when using TLS)
		 */
		public final ByteChannel io;
		/**
		 * The {@link SocketChannel} connection
		 */
		public final SocketChannel c;
		/**
		 * The server peer of this connection
		 */
		public final ServerPeer sp;
		/**
		 * The client peer of this connection
		 */
		public final ClientPeer cp;
		/**
		 * Server peer handler. Invoking this will simulate a function being invoked on this peer. If {@link #isServerSideConnection()} is true, then 
		 * calling this will invoke the method locally. One could override this handler and link to the original to intercept calls.
		 * @see #cph
		 */
		public BiConsumer<Short, Object[]> sph = null;
		/**
		 * Client peer handler. Invoking this will simulate a function being invoked on this peer. If {@link #isServerSideConnection()} is false, then 
		 * calling this will invoke the method locally. One could override this handler and link to the original to intercept calls.
		 * @see #sph
		 */
		public BiConsumer<Short, Object[]> cph = null;
		Thread virtual_Listener;
		NetServer<ServerPeer, ClientPeer> ns;
		
		/**
		 * Handler for any exceptions
		 */
		public BiConsumer<String, Exception> exceptionHandlr = (_, _)->{};
		/**
		 * Handler for custom data
		 */
		public Consumer<byte[]> customDataHandlr = (_)->{};
		/**
		 * Handler for messages
		 */
		public Consumer<String> msgHandlr = (_)->{};
		
//		SSLEngine sslEngine;
		private SocketAddress remote, local;
		boolean isWebConnection = false;
		boolean isWebSocketConnection = false;
		ByteBuffer plainDataCon = null;
		volatile boolean valid = false;
		
		NetConnection(NetBuffer rb, NetBuffer wb, SocketChannel c, ServerPeer sp, ClientPeer cp, ByteChannel cc) {
			this.rb = rb;
			this.wb = wb;
			this.c = c;
			this.sp = sp;
			this.cp = cp;
			this.io = cc;
			try {
				this.remote = c.getRemoteAddress();
				this.local = c.getLocalAddress();
			} catch (IOException e) {}
		}
		/**
		 * Returns the {@link NetServer},if this is a server side connection (see {@link #isServerSideConnection()})
		 * @return the {@link NetServer}, or null
		 */
		public NetServer<ServerPeer, ClientPeer> getNetServer() {return ns;}
		
		/**
		 * Validates this connection, eg. if done on the server side this client will not be disconnected by the inbuilt timeout system.
		 */
		public void validate() {
			valid = true;
		}
		
		/**
		 * Closes the connection
		 * @throws IOException 
		 */
		@Override
		public void close() throws IOException {
			if(isServerSideConnection()) {
//				ns.markRemoved(c, "Closing Connection".getBytes());
				ns.disconnect(c);
			} else {
				virtual_Listener.interrupt();
				c.close();
			}
			
		}
		/**
		 * {@return if this connection was created by a {@link NetServer}}
		 */
		public boolean isServerSideConnection() {
			return ns != null;
		}
		@Override
		public String toString() {
			return "["+(isServerSideConnection()?"SS":"CS")+"-NetConnection: "+local+"->"+remote+"]";
		}
		/**
		 * {@return if this connection is by a web-client}
		 */
		public boolean isWebConnection() {
			return isWebConnection;
		}
		
	}
	
	
	/**
	 * A buffer that can either write or read packets from a socket connection. The basic packet protocol is implemented here, as follows:<br>
	 * 1x byte: version -> version of the protocol<br>
	 * 1x byte: extra -> type of the packet, either TYPE_STRING_MESSAGE(0), TYPE_FUNCTION_INVOKE(2) or TYPE_CUSTOM(3)<br>
	 * 2x byte: length -> length of the remaining data after the header<br>
	 * lenght x bytes -> the payload<br>
	 * <br>
	 * When type is TYPE_STRING_MESSAGE, the remaining bytes are an UTF-8 representation of a message.<br>
	 * when type is TYPE_FUNCTION_INVOKE, the payload is structured as follows:<br>
	 * 2x byte: hash of the function to invoke- (see {@link Net#getUUID(Method)})<br>
	 * ? byte: serialized array of primitive arguments. (see {@link Net#serializeObjects(DataOutputStream, Object...)})<br>
	 */
	public static class NetBuffer {
		/**
		 * The buffer capacity used to read from NIO
		 */
		public static int BUFFER_CAPACITY = 1024; //MAX Packet size
		private ByteBuffer buffer = ByteBuffer.allocateDirect(BUFFER_CAPACITY); //Has no backing array, only accessed by one thread. Also acts as lock till close
//		private ShortBuffer buffer_short = buffer.asShortBuffer();
		volatile private long bytesTransferred = 0, packetsTransferred = 0;
		volatile SelectionKey writeSelectionKey = null;
		volatile boolean[] enableWebConnections = {false};
		volatile boolean[] enableNormalConnections = {true};
		volatile boolean open = true;
		public final Object lock;
		public NetBuffer(Object lock) {
			this.lock = lock;
		}
//		/**
//		 * Every data packet will be first processed by the processor. One could override this processor and link to the original to modify the data.
//		 * The size and contents of the data may vary.
//		 */
//		public Function<ByteBuffer, ByteBuffer> processor = (a)->{return a;};
		/**
		 * the version of the packet
		 */
		volatile public byte version;
		/**
		 * the type of the packet
		 */
		volatile public byte extra;
//		/**
//		 * reserved field of the packet
//		 */
//		public byte key;
		/**
		 * the length of the packet
		 */
		volatile public short length;
//		/**
//		 * random data of the packet
//		 */
//		public short randm;
		/**
		 * the payload of the packet
		 */
		volatile public DataView data;
		public byte[] dataOut;
		private byte[] buffr2 = new byte[32]; //Buffer, contains whole packets
		/**
		 * The queue of packets to be send
		 */
		public ConcurrentLinkedQueue<Object[]> writeQueue = new ConcurrentLinkedQueue<>();
		public static record DataView(Object data, int offset, int length) {
				public DataView(byte[] data, int offset, int length) {
					this((Object)data, offset, length);
				}
				public DataView(ByteBuffer data, int offset, int length) {
					this((Object)data, offset, length);
				}
		}
		{
			reset();
		}
		/**
		 * Resets this buffer.
		 */
		public void reset() {
			version = VERSION;
			extra = 0;
//			key = (byte) System.nanoTime(); //Random
			length = Short.MAX_VALUE;
			buffer.clear();
			cl = 0;
			data = null;
			writeBuffer = null;
		}
		/**
		 * Makes the this{@link #writeSelectionKey} only interested in reading
		 */
		public void setReadOnly() {
			writeSelectionKey.interestOps(SelectionKey.OP_READ);
			writeSelectionKey.selector().wakeup();
		}
		/**
		 * Makes the this{@link #writeSelectionKey} interested in reading & writing
		 */
		public void setReadWrite() {
			writeSelectionKey.interestOps(SelectionKey.OP_READ|SelectionKey.OP_WRITE);
			writeSelectionKey.selector().wakeup();
		}
		/**
		 * {@return if the buffer is currently sending/receiving data}
		 */
		public boolean isOperating() {
			return data != null;
		}
//		/**
//		 * Constructor of the buffer. 
//		 * @param e selection key. May be null if the buffer is used for reading
//		 */
//		public NetBuffer(SelectionKey e) {
//			this.writeSelectionKey = e;
//		}
		private volatile int cl = 0; //Counter
		ByteArrayOutputStream baos;
		byte httpc = 0;
		boolean uninit = true;
		void readFrom(ByteChannel c) throws IOException {
			if(!open)
				return;
//			buffer.position(0);
			loop:
			while(true) { //Exits when 0 bytes are read
				if(length == Short.MAX_VALUE) {
					if(baos == null)
						buffer.limit(HEADER_SIZE);
					else
						buffer.clear();
					var a = c.read(buffer);
					if(a == -1)
						throw new NetCloseException();
					else if(a == 0) //when using TLS this will never return normally, but throw an exception
						return;
					bytesTransferred += a;
					if(baos != null) {
						buffer.flip();
						for(int i = 0; i < buffer.limit(); i++) {
							var da = buffer.get();
							if(!Character.isWhitespace(da))
								httpc = 0;
							else if(da == Character.LINE_SEPARATOR)
								httpc++;
							baos.write(da);
							if(httpc == 2) {
								buffer.clear();
								byte[] d = baos.toByteArray();
								baos = null;
								httpc = 0;
								onHTTPRead(d);
								continue loop;
							}
							if(bytesTransferred > MAX_HTTP_HEADER_SIZE)
								throw new NetTooMuchException("HTML Header exceeded max size!");
						}
						return;
					} else if(buffer.position() >= HEADER_SIZE) {//Header is here
						version = buffer.get(0);
						if(version >= 0 && uninit) { //Might be a plain text connection, check for http. Only if the connection is uninitialized.
							if(!enableWebConnections[0])
								throw new NetException("Webconnections not enabled!");
							baos = new ByteArrayOutputStream();
							baos.write(buffer.get(0));
							baos.write(buffer.get(1));
							baos.write(buffer.get(2));
							baos.write(buffer.get(3));
							buffer.clear();
							uninit = false;
							continue loop;
						} else if(!enableNormalConnections[0])
							throw new NetException("Normal connections not enabled!");
						version = (byte) (version&0x7e);
						if(version > VERSION)
							throw new NetException("Incompatible versiom! This: " + VERSION + " vs remote: " + version);
						extra = buffer.get(1);
//						key = buffer.get(3);
						length = buffer.getShort(2);
//						randm = buffer.getShort(4);
						if(length < 0 || extra < 0 || version < 0)
							throw new NetException("Invalid Packet header!");
						if(buffr2.length < length || buffr2.length > length+256) //Don't reallocate arrays of similar size
							buffr2 = new byte[length];
						dataOut = buffr2;
					}
				} else {
					buffer.clear();
					buffer.limit(Math.min(buffer.capacity(), length-cl));
					int amount = c.read(buffer);
					if(amount == -1)
						throw new NetCloseException();
					else if(amount == 0)
						return;
					buffer.flip();
					buffer.get(0, dataOut, cl, buffer.limit());
					cl += amount;
					bytesTransferred += amount;
					if(cl == length) {
						onRead(dataOut, extra);
						reset();
					}
				}
			}
		}
		byte[] key = new byte[4];
		OPCODE opcode;
		int lengthp = 0;
		boolean fin;
		boolean first = true;
		boolean e = true;
		private int opcodec;
		void readFromWebSocket(ByteChannel c) throws IOException {
			if(!open)
				return;
			loop:
			while(true) { //Exits when 0 bytes are read
				if(length == Short.MAX_VALUE) {
//					System.out.println("WOOOOOOOOOOOOOOOOOOOOOOOOOOOOO + " + e);
					if(e) {
						buffer.limit(1+1+4+4); //Max header size for Websocket connections is 2bytes + short? + int + version + extra + length
					}
					var a = c.read(buffer);
//					System.err.println(a);
					if(a == -1)
						throw new NetCloseException();
					else if(a == 0) //when using TLS this will never return normally, but throw an exception
						return;
					bytesTransferred += a;
					if(buffer.position() == buffer.limit()) {//Header is here
						if(!enableWebConnections[0])
							throw new NetException("Normal connections not enabled!");
						//Start WebSocket header
						if(e) {
							int b1 = Byte.toUnsignedInt(buffer.get(0));
							int b2 = Byte.toUnsignedInt(buffer.get(1));
//							System.out.println(b1 + " bb " + b2 );
							this.fin = (b1&0xff)!=0;
							this.opcodec = (b1&0b00001111);
							if(opcodec != OPCODE.CONTINUATION.getValue())
								this.opcode = OPCODE.get(opcodec);
							else if(opcode != OPCODE.BINARY)
								throw new NetTooMuchException("Continuation frames only supported with packet protocol!");
							boolean mask = (b2&0xff)!=0;
							if(!mask) { //If client sends unmasked data, then ignore and disconnect.
								throw new NetException("Unmasked data from client! closing.");
							}
							if(!this.fin)
								throw new NetException("Framed WebSocket packets not supported!");
							int v = b2&0b01111111;
							lengthp = 0;
							if(v < 126) {
								lengthp = v;
							}else if(v == 126) {
								lengthp = Short.toUnsignedInt(buffer.getShort(2));
								buffer.limit(buffer.limit()+2);
//								System.out.println("HEADER NEEDS EXTENDING");
								e = false; //Header Buffer needs to be extended
							}else if(v == 127)
								throw new NetException("64-bit packet length not supported!");
							if(!e) //Break and wait until header is extended
								continue loop;
						}
//						System.out.println("HEADER EXTENDED? e: " + e);
						final int off = (e?2:4);
						buffer.get(off, key, 0, 4);
						e = true; //Reset value
//						System.err.println("Read Websocket header " + buffer + " " + this.opcode + " " + fin + " first: " + first + " " + lengthp+ " Key: " + Arrays.toString(key));
						//Start normal header
						if(first && opcode == OPCODE.BINARY) { //All binary data has to conform to the packet structure
							version = (byte) (buffer.get(off+4)^key[0]);
							version = (byte) (version&0x7e);
							if(version > VERSION)
								throw new NetException("Incompatible versiom! This: " + VERSION + " vs remote: " + version);
							extra = (byte) (buffer.get(off+4+1)^key[1]);
	//						key = buffer.get(3);
							this.length = (short) (((buffer.get(off+4+1+1)^key[2])<<8)|(buffer.get(off+4+1+2)^key[3]));
							
							if(this.length < 0 || extra < 0 || version < 0)
								throw new NetException("Invalid Packet header!");
							if(buffr2.length < length || buffr2.length > length+256) //Don't reallocate arrays of similar size
								buffr2 = new byte[this.length];
							dataOut = buffr2;
							first = false;
							
//							System.out.println("Read HEADER " + length + " " + (buffer.get(off+4+1+1)^key[2]) + " " + (buffer.get(off+4+1+2)^key[3]));
						} else { //First of something not binary
							this.length = (short) lengthp;
							if(buffr2.length < length || buffr2.length > length+256) //Don't reallocate arrays of similar size
								buffr2 = new byte[this.length];
							dataOut = buffr2;
//							System.out.println("GOT HEADER " + opcode);
						}
						if(fin) //If this is the final header.
							first = true;
					}
				} else {
					buffer.clear();
//					System.out.println("Data: " + length + " " + cl);
					buffer.limit(Math.min(buffer.capacity(), length-cl));
					int amount = c.read(buffer);
//					System.out.println("read " + amount);
					if(amount == -1)
						throw new NetCloseException();
					else if(amount == 0)
						return;
					buffer.flip();
					buffer.get(0, dataOut, cl, buffer.limit());
					cl += amount;
					bytesTransferred += amount;
					if(cl == length) {
						for (int i = 0; i < dataOut.length; i++) {
							dataOut[i] = (byte) (dataOut[i] ^ key[i & 0x3]);
				        }
						if(opcode == OPCODE.BINARY)
							onRead(dataOut, extra);
						else
							onReadWebSocket(dataOut, opcode);
						reset();
					}
				}
			}
		}
		/**
		 * Called when a packet of data is ready.
		 * @param data the payload
		 * @param extra the type
		 */
		public void onRead(byte[] data, byte extra) throws IOException {
					
		}
		/**
		 * Called when a WebSocket packet is received, that is not binary.
		 * @param data the data
		 * @param opcode the code (never BINARY)
		 * @throws IOException 
		 */
		public void onReadWebSocket(byte[] data, OPCODE opcode) throws IOException {
			
		}
		/**
		 * Called when a http header is recieved.
		 * @param data the header
		 */
		public void onHTTPRead(byte[] data) throws IOException {
			
		}
		volatile static int d = 0;
		/**
		 * Writes or adds a packet to the queue. These packets may be actually processed at a later time, but this function will return immediately.
		 * A packet size of 0 indicates that the connection should be terminated.
		 * @param data the payload
		 * @param extra the type
		 */
		public void write(DataView data, byte extra) {
			if(!open || !writeSelectionKey.isValid())
				throw new NetException("Buffer already closed!");
			synchronized (lock) {
				if(!open || !writeSelectionKey.isValid())
					throw new NetException("Buffer already closed!");
				if(!(data.data instanceof byte[]))
					throw new IllegalArgumentException("Data may not be something other than byte[]!");
				writeQueue.add(new Object[] {data, extra});
				setReadWrite();
////				Thread.dumpStack();
////				System.err.println("DATA WRITE CALL");
////				if(this.data != null || !writeQueue.isEmpty()&&fair)) {
////					writeQueue.add(new Object[] {data, extra});
////					return;
////				}
////				System.out.println("writing data! " + writeQueue.size());
//				this.data = data;
//				this.extra = extra;
////				this.randm = (short) System.nanoTime();
//				buffer.clear();
//				buffer.put((byte) (version|0x80));
//				buffer.put(extra);
////				buffer.put(3, key);
////				buffer.position(4);
//				if(this.data.length > Short.MAX_VALUE)
//					throw new IllegalArgumentException("Data exceeded max packet size of " + Short.MAX_VALUE + "! ("+this.data.length+")");
//				buffer.putShort((short) this.data.length);
////				buffer.putShort(randm);
//				buffer.put(data.data, data.offset, Math.min(data.length, buffer.remaining()));
//				buffer.flip();
//				cl = -4;
////				setReadWrite();
			}
		}
		/**
		 * Writes or adds raw data to the queue. The data may be actually processed at a later time, but this function will return immediately.
		 * A packet size of 0 indicates that the connection should be terminated.
		 * @param data the data
		 */
		public void writeRaw(byte[] data) {
			writeRaw(new DataView(data, 0, data.length));
		}
		/**
		 * Writes or adds raw data to the queue. The data may be actually processed at a later time, but this function will return immediately.
		 * A packet size of 0 indicates that the connection should be terminated.
		 * @param data the data
		 */
		public void writeRaw(byte[] data, int offset, int length) {
			writeRaw(new DataView(data, offset, length));
		}
		/**
		 * Writes or adds raw data to the queue. The data may be actually processed at a later time, but this function will return immediately. The position and length are derived from the buffer's
		 * position and limit.
		 * A packet size of 0 indicates that the connection should be terminated.
		 * @param data the data
		 */
		public void writeRaw(ByteBuffer data) {
			writeRaw(new DataView(data, data.position(), data.limit()-data.position()));
		}
		/**
		 * Writes or adds raw data to the queue. The data may be actually processed at a later time, but this function will return immediately.
		 * A packet size of 0 indicates that the connection should be terminated.
		 * @param data the data
		 */
		public void writeRaw(ByteBuffer data, int offset, int length) {
			writeRaw(new DataView(data, offset, length));
		}
		/**
		 * Writes or adds raw data to the queue. The data may be actually processed at a later time, but this function will return immediately.
		 * A packet size of 0 indicates that the connection should be terminated.
		 * @param data the data
		 */
		public void writeRaw(DataView data) {
			if(!open)
				throw new NetException("Buffer already closed!");
			synchronized (lock) {
				if(!open)
					throw new NetException("Buffer already closed!");
				writeQueue.add(new Object[] {data});
				setReadWrite();
//				if(this.data != null) {
//					writeQueue.add(new Object[] {data});
//					return;
//				}
//				this.data = data;
//				buffer.clear();
//				buffer.put(data.data, data.offset, Math.min(data.length, buffer.capacity()));
//				buffer.flip();
//				cl = 0;
//				setReadWrite();
			}
		}
		/**
		 * Writes a death packet, marking that the output should close after everything has been written.
		 */
		public void writeDeath() {
			writeRaw(new byte[0]);
		}
		/**
		 * Waits until the queue is empty or a death packet arrives (signaling connection close).
		 * @param milli the timeout in milliseconds
		 * @throws InterruptedException if that happens
		 */
		public void waitTillDeath(int milli) throws InterruptedException {
			synchronized (buffer) { //Awake
				buffer.wait(milli);
			}
		}
//		ByteArrayOutputStream bads;
		private ByteBuffer writeBuffer = null;
		void writeTo(ByteChannel c) throws IOException {
			if(!open)
				return;
			synchronized (lock) {
				if(!open)
					return;
				if(data == null) {
					if(writeQueue.isEmpty()) { //Key is set, but no data is transmitting.
						setReadOnly(); //remove key
						return;
					}else { //Poll queue and put into data.
						var o = writeQueue.poll();
						if(o.length == 1) {
//							writeRaw((DataView) o[0]);
							this.data = (DataView) o[0];
							if(this.data.data instanceof byte[] dat) {
//							System.out.println("Write RAW DATA " + data.length + " " + Thread.currentThread());
								buffer.clear();
								buffer.put(dat, data.offset, Math.min(data.length, buffer.capacity()));
								buffer.flip();
								writeBuffer = buffer;
							} else {
								writeBuffer = ((ByteBuffer) this.data.data).asReadOnlyBuffer();
								writeBuffer.position(data.offset);
								writeBuffer.limit(data.offset+data.length);
							}
							cl = 0;
//							bads = new ByteArrayOutputStream(data.length);
						}else {
							this.data = (DataView) o[0];
							this.extra = (byte) o[1];
//							this.randm = (short) System.nanoTime();
							buffer.clear();
							buffer.put((byte) (version|0x80));
							buffer.put(extra);
//							buffer.put(3, key);
//							buffer.position(4);
							if(this.data.length > Short.MAX_VALUE)
								throw new IllegalArgumentException("Data exceeded max packet size of " + Short.MAX_VALUE + "! ("+this.data.length+")");
							buffer.putShort((short) this.data.length);
//							buffer.putShort(randm);
							buffer.put((byte[]) data.data, data.offset, Math.min(data.length, buffer.remaining()));
							buffer.flip();
							cl = -4;
//							write((DataView)o[0], (byte)o[1]);
							writeBuffer = buffer;
						}
					}
				}
//				System.out.println("write! " + data.length + " " + cl + " " + buffer);
				if(data.length == 0) {
					synchronized (buffer) { //Awake
						buffer.notifyAll();
					}
					throw new NetCloseException(); //Closing
				}
				while(true) { //Exits when 0 bytes are written
					if(cl == data.length) { //First check if everything has been transmitted
						reset();
						packetsTransferred++;
//						Files.write(new File("testfil.zip").toPath(), bads.toByteArray(), StandardOpenOption.CREATE);
						return;
					}
//					int a = buffer.position();
//					for(int i = buffer.position(); i < buffer.limit(); i++)
//						bads.write(buffer.get(i));
//					int p = buffer.position();
//					System.out.println("write...");
					int amount = c.write(writeBuffer);
					//Hello dear future readers. Here lies an unfixed bug, where, when transmitting a big packet, and it getting split up into hundreds of smaller packets (of size MAX_BUFFER_SIZE),
					//Then it might happen that that data gets corrupted. Instead, try sending smaller packets or sending directly with an ByteBuffer to avoid this issue. : ) Liebe Grüße
//					System.out.println(amount + " " + buffer + " " + data.length + " " + cl);
//					System.out.println("wrote");
//					for(int k = a; k < a+amount; k++)
//						System.out.println(buffer.get(k));
					if(amount == -1)
						throw new NetCloseException();
					cl += amount;
					bytesTransferred += amount;
					
					if(buffer.position() == buffer.limit() && (writeBuffer == buffer)) { //if buffer was completely written, so update it. Amount may be 0 at this point. Only if writing with buffer.
//						System.out.println("Next page!");
						buffer.clear();
						buffer.put((byte[]) data.data, cl+data.offset, Math.min(buffer.capacity(), data.length-cl));
						buffer.flip();
//						System.out.println("Write RAW DATA " + data.length + " " + cl + "-> " + (cl+buffer.limit()));
					} else if(amount == 0) {//when using TLS this will never return normally, but throw an exception. If amount is 0, then the client simply is not reading any data currently.
//						System.out.println("STALL");
						return;
					}
				}
			}
		}
		/**
		 * {@return the total amount of bytes send}
		 */
		public long getBytesTransferred() {
			return bytesTransferred;
		}
		/**
		 * {@return the total amount of packets send}
		 */
		public long getPacketsTransferred() {
			return packetsTransferred;
		}
		/**
		 * {@return if this buffer has data to be written}
		 */
		public boolean hasDataToSend() {
			return !(data == null && writeQueue.isEmpty());
		}
		public void shutdown() {
			open = false;
		}
	}
	/**
	 * Interface for any client peer.
	 */
	public static interface ClientPeerI extends PeerI{
		
	}
	/**
	 * Interface for any server peer
	 */
	public static interface ServerPeerI extends PeerI{
		
	}
	/**
	 * private interface implementing basic methods
	 */
	private static interface PeerI{
		/**
		 * {@return the connection}
		 */
		public default NetConnection<?, ?> getConnection() {
			throw new RuntimeException("Not available!");
		}
		/**
		 * Called when the connection was closed
		 */
		public default void onDisconnect() {
		}
		/**
		 * Called when the connection is established
		 * @param channel the channel
		 * @param clientPeer the other peer
		 */
		public default void onConnect(SocketChannel channel, NetConnection<?, ?> connection) {
			
		}
		/**
		 * Called when the connection has an exception. Normally the connection also gets closed.
		 * @param e the exception
		 */
		public default void onException(Exception e) {
			
		}
		/**
		 * Called when the connection is marked as reading plain data only and data arrives. The data may be fragmented if It's too big.
		 * @param pd the data buffer
		 * @param length the length of the data
		 */
		public default void onPlainData(ByteBuffer pd, int length) {
			
		}
		/**
		 * Called before any data starts to be is written
		 */
		public default void onWriteBegin() {
			
		}
		/**
		 * Called when all data has been written.
		 */
		public default void onWriteEnd() {
			
		}
	}
	/**
	 * Only functions marked with this annotation may be used for networking calls
	 */
	@Target(ElementType.METHOD)
	@Retention(RetentionPolicy.RUNTIME)
	public static @interface Networking {
		short uuid();
	}
	/**
	 * Reduced function of {@link #connect(SocketChannel, ClientPeerI, Class, Class, BiConsumer, Consumer, Consumer, ThreadFactory, ByteChannel, boolean)}
	 */
	public static <ServerPeer extends ServerPeerI, ClientPeer extends ClientPeerI> NetConnection<ServerPeer, ClientPeer> connect(final SocketChannel client, final ClientPeer cp, final Class<ServerPeer> server_peer,
			final Class<ClientPeer> client_peer) throws IOException {
		return connect(client, cp, server_peer, client_peer, null, null, null, Thread.ofPlatform().factory(), client, false);
	}
	/**
	 * Reduced function of {@link #connect(SocketChannel, ClientPeerI, Class, Class, BiConsumer, Consumer, Consumer, ThreadFactory, ByteChannel, boolean)}
	 */
	public static <ServerPeer extends ServerPeerI, ClientPeer extends ClientPeerI> NetConnection<ServerPeer, ClientPeer> connect(final SocketChannel client, final ClientPeer cp, final Class<ServerPeer> server_peer,
			final Class<ClientPeer> client_peer, final ByteChannel io) throws IOException {
		return connect(client, cp, server_peer, client_peer, null, null, null, Thread.ofPlatform().factory(), io, false);
	}
	/**
	 * Reduced function of {@link #connect(SocketChannel, ClientPeerI, Class, Class, BiConsumer, Consumer, Consumer, ThreadFactory, ByteChannel, boolean)}
	 */
	public static <ServerPeer extends ServerPeerI, ClientPeer extends ClientPeerI> NetConnection<ServerPeer, ClientPeer> connect(final SocketChannel client, final ClientPeer cp, final Class<ServerPeer> server_peer,
			final Class<ClientPeer> client_peer, final ByteChannel io, boolean plainDataConnection) throws IOException {
		return connect(client, cp, server_peer, client_peer, null, null, null, Thread.ofPlatform().factory(), io, plainDataConnection);
	}
//	private static ConcurrentHashMap<Object, Object> aiudhoa = new ConcurrentHashMap();
	/**
	 * Creates a connection to the specified address.
	 * @param <ServerPeer> server peer type
	 * @param <ClientPeer> client peer type
	 * @param client the {@link SocketChannel} to use
	 * @param cp the client peer that will be invoked by the server
	 * @param server_peer the class of the server peer.
	 * @param client_peer the class of the client peer.
	 * @param exceptionHandlr the exception handler, or null.
	 * @param msgHandlr the message handler, or null.
	 * @param customDataHandlr the custom data handler, or null.
	 * @param factory the thread factory used to create the thread.
	 * @param io the {@link ByteChannel} used for read-/writing. May be the same as client, or not (eg. when using TLS)
	 * @param plainDataConnection if the received data should be routed directly to the plain data callback and not be processed further. 
	 * @return the {@link NetConnection}
	 * @throws IllegalArgumentException if either client_peer or server_peer is not an interface, or any non-ignored method of them fails the {@link Net#check(Method)}
	 * @throws IOException if any IO-exception occurs
	 */
	public static <ServerPeer extends ServerPeerI, ClientPeer extends ClientPeerI> NetConnection<ServerPeer, ClientPeer> connect(final SocketChannel client, final ClientPeer cp, final Class<ServerPeer> server_peer,
			final Class<ClientPeer> client_peer, BiConsumer<String, Exception> exceptionHandlr, Consumer<String> msgHandlr, Consumer<byte[]> customDataHandlr, final ThreadFactory factory,
			final ByteChannel io, final boolean plainDataConnection) throws IOException {
		Net.init();
		if(!client_peer.isInterface())
			throw new IllegalArgumentException("Client peer class has to be an interface!");
		if(!server_peer.isInterface())
			throw new IllegalArgumentException("Server peer class has to be an interface!");
		if(exceptionHandlr == null)
			exceptionHandlr = (a, b)->{
				if(DEBUG_MODE) {
					System.err.println("[Client]: " + a + " " + b);
					b.printStackTrace();
				}
			};
		if(msgHandlr == null)
			msgHandlr = (a)->{
				if(DEBUG_MODE)
					System.out.println("[Client]: Message from server: " + a);
			};
		if(customDataHandlr == null)
			customDataHandlr = (_)->{};
		@SuppressWarnings("unchecked")
		final NetConnection<ServerPeer, ClientPeer>[] netConA = new NetConnection[1];
//		final Method[] speerMethods = server_peer.getDeclaredMethods();
		final Method[] cpeerMethods = client_peer.getDeclaredMethods();
		final HashMap<Short, PrimitiveType[]> methodParameterMap = new HashMap<>();
		final HashMap<Short, Method> cpeerMethodsMap = new HashMap<>();
		PrimitiveType[] ds = null;
//		for(int i = 0; i < speerMethods.length; i++) {
//			if(Net.check(speerMethods[i]))
//				if((ds = methodParameterMap.put((short) Net.getUUID(speerMethods[i]), getParameters(speerMethods[i]))) != null)
//					throw new Error("Duplicate method UUID("+Net.getUUID(speerMethods[i])+")! " + ds + " & " + speerMethods[i]);
//		}
		for(int i = 0; i < cpeerMethods.length; i++) {
			if(Net.check(cpeerMethods[i])) {
				short id = Net.getUUID(cpeerMethods[i]);
				if((ds = methodParameterMap.put(id, getParameters(cpeerMethods[i]))) != null)
						throw new Error("Duplicate method UUID("+id+")! " + ds + " & " + cpeerMethods[i]);
				cpeerMethodsMap.put(id, cpeerMethods[i]);
			}
		}
		final Selector s = Selector.open();
		final var skey = client.register(s, SelectionKey.OP_READ, client);
		final NetBuffer read_buffer = plainDataConnection ? null : new NetBuffer(client) {
			private Object[] args;
			@Override
			public void onRead(byte[] data, byte extra) {
				switch (extra) {
				case Net.TYPE_STRING_MESSAGE: {
					netConA[0].msgHandlr.accept(new String(data));
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
						netConA[0].cph.accept(id, args);
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
					break;
				}
				case Net.TYPE_CUSTOM: {
					netConA[0].customDataHandlr.accept(data);
				}
				default:
					throw new IllegalArgumentException("Unexpected value: " + extra);
				}
			}
		};
		if(read_buffer != null) {
			read_buffer.enableNormalConnections[0] = true;
			read_buffer.enableWebConnections[0] = true;
		}
		final NetBuffer write_buffer = new NetBuffer(client) {
			@Override
			public void setReadWrite() {
				super.setReadWrite();
				cp.onWriteBegin();
			}
			@Override
			public void setReadOnly() {
				super.setReadOnly();
				cp.onWriteEnd();
			}
		};
		write_buffer.writeSelectionKey = skey;
		
		final Thread virtual_Listener = factory.newThread(()->{
//			System.out.println("a");
//			synchronized (netConA) { //Wait until initialized
//				try {
//					netConA.wait();
//				} catch (InterruptedException e) {
//					e.printStackTrace();
//				}
//			}
//			aiudhoa.put(netConA, "b");
//			System.err.println(aiudhoa.size() + " " + client.isConnected());
			final ByteBuffer bb = plainDataConnection ? ByteBuffer.allocateDirect(PLAIN_DATA_BUFFER_SIZE) : null;
			loop:
			while(client.isConnected() || client.isConnectionPending()) {
				try {
//					if(client.isConnectionPending())
					doSelect(s, (key) ->{
						try {
							 if(key.isReadable()) {
								 if(plainDataConnection) {
									 loop: //Exposed functionality, similar to which is seen in the NetBuffer class
										 while(true) {
												bb.clear();
												int d = io.read(bb);
												if(d == -1)
													throw new NetCloseException();
												else if(d == 0)
													break loop;
												cp.onPlainData(bb, d);
											}
								 } else
									read_buffer.readFrom(io);
							 } else if(key.isWritable()) {
								 write_buffer.writeTo(io);
							 }
						} catch (Exception d) {
							if(!isWouldBlockException.test(d))
								throw d;
							//Do nothing. the readFrom/writeTo methods always throw the WouldBlockException when using TLS
                        }
					});
				} catch(NetCloseException|ClosedChannelException a) { //Normal disconnection
					try {client.close();} catch (IOException e1) {}
					break loop;
				}catch (Exception e) { //Client exception
					netConA[0].exceptionHandlr.accept("Clientside packet handling exception", e);
					netConA[0].cp.onException(e);
					try {client.close();} catch (IOException e1) {}
					break loop;
				}
			}
			cp.onDisconnect();
			
		});
		virtual_Listener.setName("NetConnection Client Thread " + client.getLocalAddress() + " -> " + client.getRemoteAddress());
		@SuppressWarnings("unchecked")
		final ServerPeer server = (ServerPeer) Proxy.newProxyInstance(server_peer.getClassLoader(), new Class<?>[] {server_peer}, new InvocationHandler() {
			@Override
			public Object invoke(Object proxy, Method method, Object[] args) throws Exception {
				short id = (short) Net.getUUID(method);
				if(id != -1) {
					if(!client.isConnected())
						throw new NotYetConnectedException();
					netConA[0].sph.accept(id, args);
					return null; //Return Future
				}
				else if(method.equals(M_GETCONNECTION)) return netConA[0];
				else if(method.equals(M_EQUALS)){return this.equals(args[0]);}
				else if(method.equals(M_HASHCODE)) {return this.hashCode();}
				else if(method.equals(M_TOSTRING)) {return "{"+client+"}";}
				throw new IllegalAccessException("Not available! ("+method+")");
			}
		});
		NetConnection<ServerPeer, ClientPeer> netCon = new NetConnection<ServerPeer, ClientPeer>(read_buffer, write_buffer, client, server, cp, io);
		netCon.valid = true; //There is no validation process/Not implemented
		netConA[0] = netCon;
		netCon.virtual_Listener = virtual_Listener;
		netCon.cph = (a, b)->{try {cpeerMethodsMap.get(a).invoke(cp, b);} catch (IllegalAccessException | InvocationTargetException e) {throw new RuntimeException(e);}};
		netCon.sph = (a, b)->{try {Net.writeCall(write_buffer, a, b);} catch (IOException e) {throw new RuntimeException(e);}};
		netCon.exceptionHandlr = exceptionHandlr;
		netCon.customDataHandlr = customDataHandlr;
		netCon.msgHandlr = msgHandlr;
//		synchronized (netConA) {
//			netConA.notifyAll();
//		}
		client.finishConnect();
		cp.onConnect(client, netCon);
		virtual_Listener.start();
		return netCon;
	}
	/**
	 * Returns the UUID of this method. This method has to have the {@link Networking} annotation present, or else -1 is returned.
	 * @param method the method. 
	 * @return the UUID
	 */
	public static short getUUID(Method method) {
		var annotation = method.getAnnotation(Networking.class);
		return annotation==null?-1:annotation.uuid();
//		annotation.uuid
//		int i = method.getName().hashCode();
//		var c = method.getParameters();
//		for(int b = 0; b < method.getParameterCount(); b++)
//			i ^= (b+1)*c[b].hashCode());
//		return (short) i;
	}
	/**
	 * @param method
	 * {@return returns the parameters of this method}
	 */
	public static PrimitiveType[] getParameters(Method method) {
		var pm = method.getParameterTypes();
		PrimitiveType[] res = new PrimitiveType[pm.length];
		for(int i = 0; i < res.length; i++)
			res[i] = TYPE_MAP.get(pm[i]);
		return res;
	}
	
	/**
     * Modified version of the doSelect method found in {@link Selector}
     */
    static int doSelect(Selector s, ThrowingConsumer<SelectionKey> action)
        throws Exception
    {
        synchronized (s) {
            Set<SelectionKey> selectedKeys = s.selectedKeys();
            synchronized (selectedKeys) {
                selectedKeys.clear();
                int numKeySelected = s.select();

                // copy selected-key set as action may remove keys
                Set<SelectionKey> keysToConsume = Set.copyOf(selectedKeys);
                assert keysToConsume.size() == numKeySelected;
                selectedKeys.clear();

                // invoke action for each selected key
                for (SelectionKey k : keysToConsume) {
                	action.accept(k);
                    if (!s.isOpen())
                        throw new ClosedSelectorException();
                }

                return numKeySelected;
            }
        }
    }
	
    public static void writeCall(NetBuffer buffer, int id, Object[] args) throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		DataOutputStream dos = new DataOutputStream(baos);
		dos.writeShort(id);
		if(args != null)
			serializeObjects(dos, args);
		buffer.write(new Kartoffel.Licht.Networking.Net.NetBuffer.DataView(baos.toByteArray(), 0, baos.size()), TYPE_FUNCTION_INVOKE);
	}
    public static void writeCallWebSocket(NetBuffer buffer, int id, Object[] args) throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		DataOutputStream dos = new DataOutputStream(baos);
		dos.write(VERSION);
		dos.write(TYPE_FUNCTION_INVOKE);
		//Don't send the size again.
		dos.writeShort(id);
		if(args != null)
			serializeObjects(dos, args);
		var payload = baos.toByteArray();
		writeWebSocketPacket(buffer, OPCODE.BINARY, payload);
	}
    public static void writeWebSocketPacket(NetBuffer buffer, OPCODE opcode, byte[] data) throws IOException {
    	ByteArrayOutputStream baos = new ByteArrayOutputStream();
		DataOutputStream dos = new DataOutputStream(baos);
		dos.writeByte(opcode.getValue()|(128));
		int length = data.length;
		if(length < 126) {
			 dos.writeByte(length);
		 }else if(length <= 0xffff) {
			 dos.writeByte(126); //-2
			 dos.writeShort(length);
		 } else {
			 dos.writeByte((127)); //-1
			 dos.writeLong(length);
		 }
		dos.write(data);
		buffer.writeRaw(baos.toByteArray());
	}
	/**
	 * Deserializes objects given their type.
	 * @param dis input
	 * @param types the types, may only be classes of primitives or their arrays
	 * @return an array of primitives, or their arrays
	 * @throws IOException if any IO-exception occurs
	 */
	public static Object[] deserializeObjects(final DataInputStream dis, final PrimitiveType[] types, Object[] res) throws IOException {
		if(res == null)
			res = new Object[types.length];
		else if(res.length != types.length)
			res = new Object[types.length];
		for(int i = 0; i < types.length; i++) {
			switch (types[i]) {
			case INT: {
				res[i] = (int)readVarInt(dis);
				break;
			}
			case SHORT: {
				res[i] = (short)readVarInt(dis);
				break;
			}
			case BYTE: {
				res[i] = (byte)readVarInt(dis);
				break;
			}
			case LONG: {
				res[i] = (long)readVarInt(dis);
				break;
			}
			case BOOLEAN: {
				res[i] = dis.readBoolean();
				break;
			}
			case CHAR: {
				res[i] = (char)readVarInt(dis);
				break;
			}
			case DOUBLE: {
				res[i] = dis.readDouble();
				break;
			}
			case FLOAT: {
				res[i] = dis.readFloat();
				break;
			}
			//Arrays
			case INT_ARRAY: {
				int a = (int) readVarInt(dis);
				int[] ar = new int[a];
				for(int l = 0; l < a; l++)
					ar[l] = dis.readInt();
				res[i] = ar;
				break;
			}
			case SHORT_ARRAY: {
				int a = (int) readVarInt(dis);
				short[] ar = new short[a];
				for(int l = 0; l < a; l++)
					ar[l] = dis.readShort();
				res[i] = ar;
				break;
			}
			case BYTE_ARRAY: {
				int a = (int) readVarInt(dis);
				byte[] ar = new byte[a];
				dis.read(ar, 0, a);
				res[i] = ar;
				break;
			}
			case LONG_ARRAY: {
				int a = (int) readVarInt(dis);
				long[] ar = new long[a];
				for(int l = 0; l < a; l++)
					ar[l] = (long) readVarInt(dis);
				res[i] = ar;
				break;
			}
			case BOOLEAN_ARRAY: {
				int a = (int) readVarInt(dis);
				boolean[] ar = new boolean[a];
				for(int l = 0; l < a; l++)
					ar[l] = dis.readBoolean();
				res[i] = ar;
				break;
			}
			case CHAR_ARRAY: {
				int a = (int) readVarInt(dis);
				char[] ar = new char[a];
				for(int l = 0; l < a; l++)
					ar[l] = (char) readVarInt(dis);
				res[i] = ar;
				break;
			}
			case DOUBLE_ARRAY: {
				int a = (int) readVarInt(dis);
				double[] ar = new double[a];
				for(int l = 0; l < a; l++)
					ar[l] = dis.readDouble();
				res[i] = ar;
				break;
			}
			case FLOAT_ARRAY: {
				int a = (int) readVarInt(dis);
				float[] ar = new float[a];
				for(int l = 0; l < a; l++)
					ar[l] = dis.readFloat();
				res[i] = ar;
				break;
			}
			default:
				throw new IllegalArgumentException("Unexpected value: '" + types[i]);
			}
		}
		return res;
	}
	/**
	 * Serializes objects
	 * @param dos output
	 * @param objects array of objects. May only be primitives or their arrays
	 * @throws IOException if any IO-exception occurs
	 */
	public static void serializeObjects(DataOutputStream dos, Object...objects) throws IOException {
		for(Object o : objects) {
			switch (o) {
			case Integer a: {
				writeVarInt(dos, a);
				break;
			}
			case Short a: {
				writeVarInt(dos, a);
				break;
			}
			case Byte a: {
				writeVarInt(dos, a);
				break;
			}
			case Long a: {
				writeVarInt(dos, a);
				break;
			}
			case Boolean a: {
				dos.writeBoolean(a);
				break;
			}
			case Character a: {
				writeVarInt(dos, a);
				break;
			}
			case Double a: {
				dos.writeDouble(a);
				break;
			}
			case Float a: {
				dos.writeFloat(a);
				break;
			}
			//Arrays (Long and char arrays are var-ints!)
			case int[] a: {
				writeVarInt(dos, a.length);
				for(int i = 0; i < a.length; i++)
					dos.writeInt(a[i]);
				break;
			}
			case short[] a: {
				writeVarInt(dos, a.length);
				for(int i = 0; i < a.length; i++)
					dos.writeShort(a[i]);
				break;
			}
			case long[] a: {
				writeVarInt(dos, a.length);
				for(int i = 0; i < a.length; i++)
					writeVarInt(dos, a[i]);
				break;
			}
			case byte[] a: {
				writeVarInt(dos, a.length);
				dos.write(a);
				break;
			}
			case boolean[] a: {
				writeVarInt(dos, a.length);
				for(int i = 0; i < a.length; i++)
					dos.writeBoolean(a[i]);;
				break;
			}
			case char[] a: {
				writeVarInt(dos, a.length);
				for(int i = 0; i < a.length; i++)
					writeVarInt(dos, a[i]);
				break;
			}
			case double[] a: {
				writeVarInt(dos, a.length);
				for(int i = 0; i < a.length; i++)
					dos.writeDouble(a[i]);
				break;
			}
			case float[] a: {
				writeVarInt(dos, a.length);
				for(int i = 0; i < a.length; i++)
					dos.writeFloat(a[i]);
				break;
			}
			default:
				throw new IllegalArgumentException("Unexpected value: '" + o + "' (" + o.getClass()+")");
			}
		}
	}
	/**
	 * Checks if a method is valid to use for networking.
	 * @param m the method
	 * @return if this method should not be ignored
	 * @throws Error if one or more of the conditions are true: <br>
	 * - the method does not return void<br>
	 * - the method has a parameter that is not a primitive or an array of such<br>
	 */
	public static boolean check(Method m) throws Error{
		if(m.getDeclaredAnnotation(Networking.class) == null)
			return false;
		if(m.getReturnType() != void.class)
			throw new Error("Return type of method "+m+" has to be void! ("+m.getReturnType()+")");
		var b = m.getParameterTypes();
		for(int l = 0; l < b.length; l++)
			if(!b[l].isArray()) {
				if(!b[l].isPrimitive())
					throw new Error("Parameter #"+l+" of method "+m+" is not a primitive! ("+b[l]+")");
			} else if(!b[l].componentType().isPrimitive())
				throw new Error("Component type of array-parameter #"+l+" of method "+m+" is not a primitive! ("+b[l]+")");
		return true;
	}
	/**AI GENERATED: writes a var-int to the stream*/
	public static void writeVarInt(OutputStream out, long value) throws IOException {
	    while ((value & 0xFFFFFFFFFFFFFF80L) != 0L) {
	        out.write(((int) value & 0x7F) | 0x80);
	        value >>>= 7;
	    }
	    out.write((int) value & 0x7F);
	}
	/**AI GENERATED: reads a var-int from the stream*/
	public static long readVarInt(InputStream in) throws IOException {
	    int numRead = 0;
	    long result = 0;
	    int read;
	    do {
	        read = in.read();
	        if (read == -1) {
	            throw new EOFException("Unexpected end of stream while reading VarLong");
	        }

	        long value = (read & 0x7F);
	        result |= (value << (7 * numRead));

	        numRead++;
	        if (numRead > 10) {
	            throw new IOException("VarLong is too long (more than 10 bytes)");
	        }
	    } while ((read & 0x80) != 0);

	    return result;
	}
	

}

