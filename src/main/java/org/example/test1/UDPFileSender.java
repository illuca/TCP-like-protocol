package org.example.test1;

import java.io.*;
import java.net.*;

public class UDPFileSender {
    public static void main(String[] args) throws IOException {
        args = new String[]{"FileToSend.txt", "127.0.0.1", "8888"};
        if (args.length != 3) {
            System.out.println("Usage: java UDPFileSender <file_path> <destination_ip> <destination_port>");
            System.exit(1);
        }

        String filePath = "FileToSend.txt";
        String destinationIp = "127.0.0.1";
        int destinationPort = Integer.parseInt("8888");

        // Create a socket for sending datagrams
        DatagramSocket socket = new DatagramSocket();

        // Read the file and divide it into smaller chunks
        FileInputStream fileInputStream = new FileInputStream(filePath);
        byte[] fileBuffer = new byte[1024];
        int bytesRead;

        while ((bytesRead = fileInputStream.read(fileBuffer)) != -1) {
            // Create a DatagramPacket with the file chunk, destination IP, and destination port
            DatagramPacket packet = new DatagramPacket(fileBuffer, bytesRead, InetAddress.getByName(destinationIp), destinationPort);

            // Send the DatagramPacket
            socket.send(packet);
        }

        // Close the file input stream and socket
        fileInputStream.close();
        socket.close();
    }
}
