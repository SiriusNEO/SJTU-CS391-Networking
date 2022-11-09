import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;


/**
 * Iperfer main class.
 * Parse command line arguments and run Server/Client
 *
 * @author SiriusNEO
 */
public class Iperfer {

    /**
	 * Main entry.
     * Usage: 
     *     As Iperfer client: java Iperfer -c -h <server hostname> -p <server port> -t <time>
     *     As Iperfer server: java Iperfer -s -p <listen port>
	 */
    public static void main(String[] args) throws IOException {
        if (args.length == 7 && args[0].equals("-c") && args[1].equals("-h") && args[3].equals("-p")
                && args[5].equals("-t")) {
            // Case 1: java Iperfer -c -h <server hostname> -p <server port> -t <time>
            int port = parsePort(args[4]);
            int time = 0;
            try {
                time = Integer.parseInt(args[6]);
            } catch (Exception e) {
                printInvalidArguments();
            }
            Client.run(args[2], port, time);
        } else if (args.length == 3 && args[0].equals("-s") && args[1].equals("-p")) {
            // Case 2: java Iperfer -s -p <listen port>
            int port = parsePort(args[2]);
            Server.run(port);
        } else {
            printInvalidArguments();
        }
    }

    /**
	 * Parse port string and check if it is in the range [1024, 65535]
     * @param portString the literal port argument
     * @return the integer value of port
	 */
    private static int parsePort(String portString) {
        int port = 0;
        try {
            port = Integer.parseInt(portString);
            if (port < 1024 || port > 65535) {
                throw new IllegalArgumentException();
            }
        } catch (Exception e) {
            System.out.println("Error: port number must be in the range 1024 to 65535");
            System.exit(1);
        }

        return port;
    }

    /**
	 * Print if parsing command line arguments fails.
	 */
    private static void printInvalidArguments() {
        System.out.println("Error: invalid arguments");
        System.exit(1);
    }
}

/**
 * Iperfer node class.
 * It is used to share some global information between class Client and class Server
 *
 * @author SiriusNEO
 */
class IperferNode {
    public static int CHUNK_SIZE = 1000; // bytes
}

/**
 * Iperfer Client.
 * It will send packets with fixed size to the server during a period of time,
 * then measure the sent data amount and rate.
 *
 * @author SiriusNEO
 */
class Client extends IperferNode {
    /**
     * Run the client.
     * @param host the host to connect.
     * @param portNumber the port to connect.
     * @param time the duration in seconds for which data should be generated.
     */
    static void run(String host, int portNumber, int time) throws IOException {
        try {
	        // 创建sockets
            Socket clientSoc = new Socket(host, portNumber);
            System.out.println("Iperfer client connecting to host: " + host + " port: " + portNumber);

            // 建立socket的输出流
            DataOutputStream outputStream = new DataOutputStream(clientSoc.getOutputStream());
    	    // 按1000bytes进行输出，直到时间time结束，期间记录发送的数据总量
            int chunksNum = 0;
            byte[] chunk = new byte[CHUNK_SIZE];
            
	        long startTime = System.currentTimeMillis();
            while (System.currentTimeMillis() - startTime < 1000 * time) {
                outputStream.write(chunk, 0, CHUNK_SIZE);
                ++ chunksNum;
            }
            
            System.out.printf("sent=%d KB\trate=%.3f Mbps\n", chunksNum, ((double)chunksNum)/1000.0/time);  // 计算发送数据总量，以及速率

            clientSoc.close();
        } catch (Exception e) {
            System.out.println("Exception happens when running Iperfer Client: " + e);
            System.exit(1);
        }
    }
}

/**
 * Iperfer Server.
 * It will accept the first TCP connection of a client, read its input to the end,
 * then measure the received data amount and rate.
 *
 * @author SiriusNEO
 */
class Server extends IperferNode {
    /**
     * Run the server.
     * @param serverPort the port which the server listens to.
     */
    static void run(int serverPort) throws IOException {
        try {
            // 创建sockets
            ServerSocket serverSoc = new ServerSocket(serverPort);
            System.out.println("Iperfer server start listening port: " + serverPort);

            // The Iperfer server should shut down after it handles one connection from a client
            // So just accept one connection here
            Socket clientSoc = serverSoc.accept();
            System.out.println("Iperfer server get a client TCP connection from ip: " + clientSoc.getRemoteSocketAddress());            

            // 建立socket的输入流 
            DataInputStream inputStream = new DataInputStream(clientSoc.getInputStream());
            
            // 按1000bytes进行读取，直到没有数据可读，期间记录发送的数据总量
            long totalReceivedLen = 0;
            byte[] readPool = new byte[CHUNK_SIZE];
            
            long startTime = System.currentTimeMillis();
            while (true) {
                // TimeUnit.MILLISECONDS.sleep(READ_WAIT); // sleep to get the full data
                int receivedLen = inputStream.read(readPool, 0, CHUNK_SIZE);
                if (receivedLen <= 0) {
                    break;
                }
                totalReceivedLen += receivedLen;
            }
            long totalTime = System.currentTimeMillis() - startTime; // ms

            System.out.printf("received=%d KB\trate=%.3f Mbps\n", totalReceivedLen/1000, (double)totalReceivedLen/totalTime/1000); // 计算接收数据总量，以及速率

            clientSoc.close();
            serverSoc.close();
        } catch (Exception e) {
            System.out.println("Exception happens when running Iperfer Server: " + e);
            System.exit(1);
        }
    }
}
