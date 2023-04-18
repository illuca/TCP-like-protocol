package org.example.test1;

import java.io.*;
import java.net.*;

public class UDPFileReceiver {
    public static void main(String[] args) throws IOException {
        args = new String[]{"FileReceived.txt", "8888"};
        if (args.length != 2) {
            System.out.println("Usage: java UDPFileReceiver <output_file_path> <listen_port>");
            System.exit(1);
        }

        String outputPath = args[0];
        int listenPort = Integer.parseInt(args[1]);

        // Create a socket for receiving datagrams
        DatagramSocket socket = new DatagramSocket(listenPort);

        // Create an output stream to write the received file
        FileOutputStream fileOutputStream = new FileOutputStream(outputPath);
        byte[] receiveBuffer = new byte[1024];
        DatagramPacket receivePacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);

        while (true) {
            // Receive the DatagramPacket
            socket.receive(receivePacket);
            int bytesRead = receivePacket.getLength();

            // Write the received data to the output file
            fileOutputStream.write(receiveBuffer, 0, bytesRead);
        }

        // Close the file output stream and socket (unreachable code in this example)
        // fileOutputStream.close();
        // socket.close();
    }
}
