package Kartoffel.Licht.Networking;



import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import org.teavm.classlib.java.lang.TRuntimeException;
import org.teavm.classlib.java.util.THashMap;
import org.teavm.classlib.java.util.TMap;
import org.teavm.classlib.java.util.function.TBiConsumer;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSClass;
import org.teavm.jso.file.Blob;
import org.teavm.jso.typedarrays.ArrayBuffer;
import org.teavm.jso.typedarrays.Int8Array;
import org.teavm.jso.websocket.WebSocket;
import org.teavm.metaprogramming.CompileTime;
import org.teavm.metaprogramming.InvocationHandler;
import org.teavm.metaprogramming.Meta;
import org.teavm.metaprogramming.Metaprogramming;
import org.teavm.metaprogramming.ReflectClass;
import org.teavm.metaprogramming.Value;
import org.teavm.metaprogramming.reflect.ReflectMethod;

import Kartoffel.Licht.Networking.Net.ClientPeerI;
import Kartoffel.Licht.Networking.Net.NetException;
import Kartoffel.Licht.Networking.Net.Networking;
import Kartoffel.Licht.Networking.Net.PrimitiveType;
import Kartoffel.Licht.Networking.Net.ServerPeerI;

@CompileTime
public class TeaNet {
	
	public static record TNetConnection<ServerPeer extends ServerPeerI>(ServerPeer serverPeer, WebSocket socket){}
	
	@JSClass
	interface TextDecoder extends org.teavm.jso.JSObject{
		public String decode(ArrayBuffer buffer);
		
	}
	@JSBody(params = {}, script = "return new TextDecoder();")
	public static native TextDecoder newTextDecoder();
	@JSBody(params = {"a", "arr"}, script = "a.set(arr)")
	public static native void putData(Int8Array a, byte[] arr);
	@Meta
    private static native Object getProxy(final Class<Object> server_peer, TBiConsumer<Short, Object[]> invoke);

	
    private static void getProxy(final ReflectClass<Object> server_peer, Value<TBiConsumer<Short, Object[]>> invoke) {
    	if(!Metaprogramming.findClass(ServerPeerI.class).isAssignableFrom(server_peer)) {
    		Metaprogramming.unsupportedCase();
    		return;
    	}
    	Value<Object> res =  Metaprogramming.proxy(server_peer, new InvocationHandler<Object>() {

			@Override
			public void invoke(Value<Object> proxy, ReflectMethod method, Value<Object>[] args) {
				final short id = TeaNet.getUUID(method);
				final int s = args.length;
				Value<Object[]> o = Metaprogramming.emit(()->new Object[s]);
				for(int i = 0; i < s; i++) {
					final int ii = i;
					final Value<Object> argss = args[i];
					Metaprogramming.emit(()->{o.get()[ii]=argss.get();});
				}
				Metaprogramming.emit(()->{
					invoke.get().accept(id, o.get());
				});
			}
		});
//    	 server_peer.getDeclaredMethods()
    	
    	Metaprogramming.exit(()->res);
    }
    @Meta
    private static native Object invokeMethod(Class<?> clazz, short id, Object a, Object[] args);

	private static THashMap<ReflectClass<?>, THashMap<Short, ReflectMethod>> methodsCache;
    private static void invokeMethod(ReflectClass<?> clazz, Value<Short> id, Value<Object> a, Value<Object[]> args) {
    	
    	if(!Metaprogramming.findClass(ClientPeerI.class).isAssignableFrom(clazz)) {
    		Metaprogramming.unsupportedCase();
    		return;
    	}
    	if(methodsCache == null) {
    		methodsCache = new THashMap<>();
    	}
    	if(!methodsCache.containsKey(clazz)) {
    		var cpeerMethods = clazz.getDeclaredMethods();
    		var res = new THashMap<Short, ReflectMethod>();
    		for(int i = 0; i < cpeerMethods.length; i++) {
    			if(TeaNet.check(cpeerMethods[i])) {
    				short idd = TeaNet.getUUID(cpeerMethods[i]);
    				Object ds;
					if((ds = res.put(idd, cpeerMethods[i])) != null)
    					throw new Error("Duplicate method UUID("+id+")! " + ds + " & " + cpeerMethods[i]);
    			}
    		}
    		methodsCache.put(clazz, res);
    	}
    	methodsCache.get(clazz).forEach((idd, m)->{
    		final short iddd = idd;
    		Metaprogramming.emit(()->{
    			if(iddd == id.get())
    				m.invoke(a.get(), args.get());
    		});
    	});
    }
    @Meta
    private static native THashMap<Short, PrimitiveType[]> checkAndGetParameters(Class<?> server_peer);
    
    private static TMap<ReflectClass<?>, PrimitiveType> TYPE_MAP;
    private static void checkAndGetParameters(final ReflectClass<?> client_peer) {
    	if(!Metaprogramming.findClass(ClientPeerI.class).isAssignableFrom(client_peer)) {
    		Metaprogramming.unsupportedCase();
    		return;
    	}
    	if(TYPE_MAP==null)
    	TYPE_MAP = TMap.ofEntries(
    		    TMap.entry(Metaprogramming.findClass(int.class),     PrimitiveType.INT),
    		    TMap.entry(Metaprogramming.findClass(long.class),    PrimitiveType.LONG),
    		    TMap.entry(Metaprogramming.findClass(double.class),  PrimitiveType.DOUBLE),
    		    TMap.entry(Metaprogramming.findClass(boolean.class), PrimitiveType.BOOLEAN),
    		    TMap.entry(Metaprogramming.findClass(byte.class),    PrimitiveType.BYTE),
    		    TMap.entry(Metaprogramming.findClass(char.class),    PrimitiveType.CHAR),
    		    TMap.entry(Metaprogramming.findClass(short.class),   PrimitiveType.SHORT),
    		    TMap.entry(Metaprogramming.findClass(float.class),   PrimitiveType.FLOAT),

    		    TMap.entry(Metaprogramming.findClass(int[].class),     PrimitiveType.INT_ARRAY),
    		    TMap.entry(Metaprogramming.findClass(long[].class),    PrimitiveType.LONG_ARRAY),
    		    TMap.entry(Metaprogramming.findClass(double[].class),  PrimitiveType.DOUBLE_ARRAY),
    		    TMap.entry(Metaprogramming.findClass(boolean[].class), PrimitiveType.BOOLEAN_ARRAY),
    		    TMap.entry(Metaprogramming.findClass(byte[].class),    PrimitiveType.BYTE_ARRAY),
    		    TMap.entry(Metaprogramming.findClass(char[].class),    PrimitiveType.CHAR_ARRAY),
    		    TMap.entry(Metaprogramming.findClass(short[].class),   PrimitiveType.SHORT_ARRAY),
    		    TMap.entry(Metaprogramming.findClass(float[].class),   PrimitiveType.FLOAT_ARRAY)
    		);
		final THashMap<Short, ReflectMethod> cpeerMethodsMap = new THashMap<>();
    	var cpeerMethods = client_peer.getDeclaredMethods();
    	var result = Metaprogramming.emit(()->new THashMap<>());
    	ReflectMethod ds = null;
    	for(int i = 0; i < cpeerMethods.length; i++) {
			if(TeaNet.check(cpeerMethods[i])) {
				short id = TeaNet.getUUID(cpeerMethods[i]);
				if((ds = cpeerMethodsMap.put(id, cpeerMethods[i])) != null)
					throw new Error("Duplicate method UUID("+id+")! " + ds + " & " + cpeerMethods[i]);
		    	final PrimitiveType[] types = getParameters(cpeerMethods[i]);
		    	final int size = types.length;
				var parr = Metaprogramming.emit(()->{var a = new Object[size];result.get().put(id, a);return a;});
				for(int l = 0; l < size; l++) {
					final int ordinal = types[l].ordinal();
					final int ll = l;
					 Metaprogramming.emit(()->parr.get()[ll] = PrimitiveType.values()[ordinal]);
				}
			}
		}
    	Metaprogramming.exit(()->result.get());
    }
	
    public static TextDecoder decoder;
	@SuppressWarnings({ "hiding", "unchecked" })
	public static <ServerPeer extends ServerPeerI, ClientPeer extends ClientPeerI> TNetConnection<ServerPeer> connect(String string, ClientPeer clientPeer, Class<ServerPeer> class1, Class<ClientPeer> class2) {
		@SuppressWarnings("deprecation")
		var socket = org.teavm.jso.websocket.WebSocket.create(string);
		socket.onError((e)->clientPeer.onException(new TRuntimeException(e.toString())));
		socket.onClose((_)->clientPeer.onDisconnect());
		final var ress = checkAndGetParameters(class2);
//		final Int8Array buff
		socket.onMessage((a)->{
//			System.out.println("TYPE: " + a.getData());
			Blob dd = (Blob) a.getData();
//			System.out.println(dd);
			dd.arrayBuffer().then((add_)->{
				ArrayBuffer add = add_;
				var data = new Int8Array(add);
				byte version = (byte) (data.get(0)&0x7e);
				if(version > Net.VERSION)
					throw new NetException("Incompatible versiom! This: " + Net.VERSION + " vs remote: " + version);
				byte extra = data.get(1);
	//			key = buffer.get(3);
	//			short length = datas.get(1);
	//			randm = buffer.getShort(4);
				if(extra < 0 || version < 0)
					throw new NetException("Invalid Packet header!");
				var ddata = new Int8Array(add_.slice(Net.HEADER_SIZE-2/*minus length*/, add.getByteLength()));
				switch (extra) {
				case Net.TYPE_STRING_MESSAGE: {
					if(decoder== null)
						decoder = newTextDecoder();
					System.out.println("[CLIENT]: " + decoder.decode(ddata.getBuffer()));
					break;
				}
				case Net.TYPE_FUNCTION_INVOKE: {
					try {
						ByteArrayInputStream bais = new ByteArrayInputStream(getByteArray(ddata));
						DataInputStream dis = new DataInputStream(bais);
						short id = dis.readShort();
						var parameters = ress.get(id);
						if(parameters == null)
							throw new NetException("Method not found " + id);
						var args = Net.deserializeObjects(dis, parameters, null);
						invokeMethod(class2, id, clientPeer, args);
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
					break;
				}
				case Net.TYPE_CUSTOM: {
					System.err.println("Can'accept custom data");
				}
				default:
					throw new IllegalArgumentException("Unexpected value: " + extra);
				}
				return add;
			});
		});
		var res = getProxy((Class<Object>) class1, (a, b)->{
//			socket.send(null) //TODO send data.
			try {
				ByteArrayOutputStream baos = new ByteArrayOutputStream();
				DataOutputStream daos = new DataOutputStream(baos);
				daos.writeByte(Net.VERSION);
				daos.writeByte(Net.TYPE_FUNCTION_INVOKE);
				daos.writeShort(0); //Placeholder
				daos.writeShort(a); //Function id
				Net.serializeObjects(daos, b); //Function parameters
				var dd = baos.toByteArray();
				short l = (short) (dd.length-4);
				dd[2] = (byte) (l>>8);
				dd[3] = (byte) l;
				socket.send(getByteArray2(dd));
			} catch (IOException e) {
				e.printStackTrace();
			}
		});
		socket.onOpen((_)->{
			clientPeer.onConnect(null, null);
		});
		return new TNetConnection<ServerPeer>((ServerPeer)res, socket);
	}
	
	public static byte[] getByteArray(Int8Array a) {
		byte[] b = new byte[a.getByteLength()];
		for(int i = 0; i < b.length; i++)
			b[i] = a.get(i);
		return b;
	}
	
	public static Int8Array getByteArray2(byte[] a) {
		Int8Array b = new Int8Array(a.length);
		putData(b, a);
		return b;
	}
	
	/**
	 * @param method
	 * {@return returns the parameters of this method}
	 */
	public static PrimitiveType[] getParameters(ReflectMethod method) {
		var pm = method.getParameterTypes();
		PrimitiveType[] res = new PrimitiveType[pm.length];
		for(int i = 0; i < res.length; i++)
			res[i] = TYPE_MAP.get(pm[i]);
		return res;
	}
	
	/**
	 * Returns the UUID of this method. This method has to have the {@link Networking} annotation present, or else -1 is returned.
	 * @param method the method. 
	 * @return the UUID
	 */
	public static short getUUID(ReflectMethod method) {
		var annotation = method.getAnnotation(Networking.class);
		return annotation==null?-1:annotation.uuid();
	}
	
	/**
	 * Checks if a method is valid to use for networking.
	 * @param m the method
	 * @return if this method should not be ignored
	 * @throws Error if one or more of the conditions are true: <br>
	 * - the method does not return void<br>
	 * - the method has a parameter that is not a primitive or an array of such<br>
	 */
	public static boolean check(ReflectMethod m) throws Error{
		if(m.getAnnotation(Networking.class) == null)
			return false;
		if(m.getReturnType() != Metaprogramming.findClass(void.class))
			throw new Error("Return type of method "+m+" has to be void! ("+m.getReturnType()+")");
		var b = m.getParameterTypes();
		for(int l = 0; l < b.length; l++)
			if(!b[l].isArray()) {
				if(!b[l].isPrimitive())
					throw new Error("Parameter #"+l+" of method "+m+" is not a primitive! ("+b[l]+")");
			} else if(!b[l].getComponentType().isPrimitive())
				throw new Error("Component type of array-parameter #"+l+" of method "+m+" is not a primitive! ("+b[l]+")");
		return true;
	}
	
}
