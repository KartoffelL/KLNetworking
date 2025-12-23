# KLNetworking
Simple networking solution for java (hosted) games

## What does the library do?


KLNetworking is a simple and rather lightweight server-client NIO-based java networking library/abstraction for simple applications and, for example, games. 
It can be either used stand-alone, with the [tls-channel](https://github.com/marianobarrios/tls-channel) library for encryption, or with tls-channel and using the TeaVM project to create web-based clients.

## What does the library not do?

KLNetworking does not guarantee/endorse strict security, nor high-performance throughput, nor functionallity with bad/weak connections

## Usage

To use, simply download any source files you want to use in your project. The project is published under the [mit-license](https://opensource.org/license/mit).

Using the library, any number of clients or servers can be created in a single application. And any number of clients can connect to a single server (creaating a Connection in the process).
But a single client can only connect to a single server. The library does not handle the creation process of the Socketchannel, but it does take it over (eg. it will be closed by the library).
The primary way to use the library is by creating interfaces representing the server and the client:
```
import Kartoffel.Licht.Networking.Net.ClientPeerI;
import Kartoffel.Licht.Networking.Net.Networking;
/**
 * The peer of the client
 */
public interface TestClient extends ClientPeerI{

	@Networking(uuid = 1444)
	public void test();
	@Networking(uuid = 3231)
	public void say(char[] msg);
	
}
```
```
import Kartoffel.Licht.Networking.Net.Networking;
import Kartoffel.Licht.Networking.Net.ServerPeerI;
/**
 * the peer of the server
 */
public interface TestServer extends ServerPeerI{
	@Networking(uuid = 2243)
	public void test(byte[] s);
	@Networking(uuid = 5545)
	public void say(int a);
	@Networking(uuid = 6487)
	public void say(double a, int b);
	
}
```
These interfaces directly contain method definitions, that can later be invoked.

Each method has to be public, void-returning and only contain primitive (-array) parameters. Each one also has to be annotated with the @Networking Annotation, specifying 
the unique method-id.<br>
`It is recommended to change that id everytime the method changes (eg. name, parameters, function)`<br>
<br>
When using in conjunction with tlschannel, the ``Net.isWouldBlockException`` field has to be set to be a predicate returning true if the provided exception is a ``tlschannel.WouldBlockException``, eg:
```
Net.isWouldBlockException = (b) -> b instanceof WouldBlockException;
```

### Usecases:

##### 1. Using the library for client connections:<br>
Include the Net.java file.<br>
Example:
```
SSLContext sslContext = createClientContext(); //Create context if using encryption
		final SocketChannel client = SocketChannel.open(); //Create socket channel
		client.connect(new InetSocketAddress("localhost", 5555)); //Connect to host
		client.configureBlocking(false); //Configure non blocking
		var clientIO = ClientTlsChannel.newBuilder(client, sslContext).build(); //Set the IO to pass through the encryption.
		var tc = new TestClient() { //Implement callback methods
			@Override
			public void test() {
				System.out.println("[Client] from server: test");
			}
			@Override
			public void say(char[] a) {
				System.out.println("[Client] from server: say something; " + new String(a));
			}
			@Override
			public void onDisconnect() {
				System.out.println("[Client] disconnected client from server");
			}
			@Override
			public void onConnect(SocketChannel channel, NetConnection<?, ?> connection) {
				System.out.println("[Client] connected to server "+ connection);
			}
		};
		clientConnection = Net.connect(client, tc, TestClientServer.class, TestClient.class, USE_ENCRYPTION?clientIO:client); //Create NetConnection.
```
##### 2. Using the library to create a server:<br>
Include the Net.java and the NetServer.java files<br>
Example:
```
KeyStore ks = KeyStore.getInstance("JKS"); //Load keys if using encryption
		try(var is = Test.class.getResourceAsStream("/keystore.jks")) {
			ks.load(is, "password".toCharArray());
		}
		SSLContext sslContext = createServerContext(ks, "password".toCharArray());
		var a = ServerSocketChannel.open(); //Create server socket channel
		a.configureBlocking(false); //Configure non blocking
		a.bind(new InetSocketAddress(5555), 0); //Bind to address
		server = new NetServer<TestServer, TestClient>(a, TestServer.class, TestClient.class, //Create the NetServer
				Thread.ofPlatform().factory(), 0) {
		
			@Override
			protected void onHTMLConnection(NetConnection<TestClientServer, TestClient> connection,
				String header) {
				connection.wb.writeRaw(("HTTP/1.1 200 OKE\nConnection: close\n\r\n\r").getBytes(StandardCharsets.UTF_8));
				connection.wb.writeRaw("<html>Nothing to be seen here</html>".getBytes(StandardCharsets.UTF_8));
				validate(connection.c); //Remove default timeout
				disconnectLater(connection.c, 1000); //Add custom one.
			}

			@Override
			public ByteChannel accept(SocketChannel channel) {
				return USE_ENCRYPTION?ServerTlsChannel.newBuilder(channel, sslContext).build():channel;
			}
			
			@Override
			public TestServer accept(TestClient channel) {
				System.out.println("[Server] New client: " + channel);
				return new TestClientServer() {
					@Override
					public void test(byte[] s) {
						System.out.println("[Server] from client: " + s.length);
					}
					@Override
					public void say(int a) {
						System.out.println("[Server] from client: say something; " + a);
					}
					@Override
					public void say(double a, int b) {
						System.out.println("[Server] from client: say something; " + a + ", " + b);
					};
					@Override
					public void onDisconnect() {
						System.out.println("[Server] client disconnected " + channel.getConnection().c);
					}
					@Override
					public void onConnect(SocketChannel channel, NetConnection<?, ?> connection) {
						System.out.println("[Server] client connected "+ connection);
					}
				};
			}
		};
		server.setHTMLConnectionsEnabled(true); //Enable html connections
		server.setValidationTimeout(5000);
		server.start(); //Start the server
```
Firstly, the server has multiple ways of getting a client disconnected. Either an exception is thrown, it is disconnected due to validation, due to some kind of time limit or because of an end-of-stream, or any kind of combination of these.
When a client initially connects, the ``public ByteChannel accept(SocketChannel channel)`` function is called, which returns the IO from/to the client, or null to reject the connection. This would be useful if using encryption. Also, since the server does not keep track of past connections, rate-limiting could also be implemented here. 
If the connection is accepted, it gets set up and the ``public TestServer accept(TestClient channel)`` method is called, which returns the methods that will be called by the client.<br>
Furthermore, if html connections are enabled, the ``protected void onHTMLConnection(NetConnection<TestClientServer, TestClient> connection, String header)`` will be called, which may respond to show, for example, a web page.<br>
<br>
Clients have to be validated after connecting, or else they will be automatically disconnected some time after the validation timeout (see ``setValidationTimeout(long millis)``), which can be done by calling ``validate(Socketchannel connection)``.
Clients can also be manually disconnected by calling ``disconnect(SocketChannel connection)``, ``disconnectLater(SocketChannel connection, int timeout)`` or ``disconnectOnWriteEnd(SocketChannel connection)``

Note that the server can accept different kinds of requests, which can be toggled:
```
server.setHTMLConnectionsEnabled(true); //Enable html connections (eg. to show a webpage)
server.setWebsocketConnectionsEnabled(true); //Enable websocket connections (eg. to allow teaVM-clients to connect)
server.setNormalConnectionsEnabled(false); //Disables normal connections (eg. won't listen to direct connections. Native clients my bypass this by creating a websocket connection instead)
```
##### 3. Using the library to create a client on teaVM:<br>
  Include the Net.java and the TeaNet.java files.<br>
  Example:
  ```
connection = TeaNet.connect("https://example.com:1334", new TestClient() {
      @Override
			public void test() {
				Window.alert("[Client] from server: test");
			}
			@Override
			public void say(char[] a) {
				Window.alert("[Client] from server: say something; " + new String(a));
			}
			@Override
			public void onDisconnect() {
				Window.alert("[Client] disconnected client from server");
			}
			@Override
			public void onConnect(SocketChannel channel, NetConnection<?, ?> connection) {
				Window.alert("[Client] connected to server "+ connection);
			}
		}, TestServer.class, TestClient.class);
  ```
### Other stuff
The library strictly relies on correctness of the supplied data. Most exceptions that occurr inside the library will result in the connection being closed.
The server itself only manages a single port. But multiple protocols could connect to the server. Currently (for the forseeable future) only the NetLibrary-protocol, and the HTTP (1.0/1.1) protocol are supported.<br>
`Newer versions of the HTTP protocol might replace the current version`<br>




