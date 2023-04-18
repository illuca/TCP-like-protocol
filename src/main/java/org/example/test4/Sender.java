package org.example.test4;

import java.io.*;
import java.net.*;

public class Sender {
    public static void main(String[] args) throws IOException, ClassNotFoundException {
        args = new String[]{"./FileToSend.txt", "127.0.0.1", "8888", "7777"};
        if (args.length != 4) {
            System.out.println("Usage: java Sender <file_path> <destination_ip> <destination_port> <ack_port>");
            System.exit(1);
        }

        String filePath = args[0];
        String destinationIp = args[1];
        int destinationPort = Integer.parseInt(args[2]);
        int ackPort = Integer.parseInt(args[3]);

        // Create a socket for sending data packets and receiving acknowledgments
        DatagramSocket socket = new DatagramSocket(ackPort);

        // Read the file and send it using the Stop-and-Wait protocol
        FileInputStream fileInputStream = new FileInputStream(filePath);
        byte[] fileBuffer = new byte[1024];
        int bytesRead;
        int sequenceNumber = 0;

        while ((bytesRead = fileInputStream.read(fileBuffer)) != -1) {
            // Create a data packet with the file chunk and sequence number
            Packet dataPacket = new Packet(Packet.Type.DATA, sequenceNumber, fileBuffer);
            byte[] sendData = serialize(dataPacket);


            // Send the data packet
            DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, InetAddress.getByName(destinationIp), destinationPort);
            socket.send(sendPacket);

            // Wait for the acknowledgment
            byte[] ackData = new byte[1024];
            DatagramPacket ackPacket = new DatagramPacket(ackData, ackData.length);
            socket.receive(ackPacket);
            Packet receivedAck = deserialize(ackPacket.getData());

            if (receivedAck.getType() == Packet.Type.ACK && receivedAck.getSequenceNumber() == sequenceNumber) {
                // Increment the sequence number (mod 2) for the next packet
                sequenceNumber = (sequenceNumber + 1) % 2;
            }
        }

        // Close the file input stream and socket
        fileInputStream.close();
        socket.close();
    }

    private static byte[] serialize(Packet packet) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        oos.writeObject(packet);
        oos.flush();
        return baos.toByteArray();
    }

    private static Packet deserialize(byte[] data) throws IOException, ClassNotFoundException {
        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(data);
        ObjectInputStream objectInputStream = new ObjectInputStream(byteArrayInputStream);
        return (Packet) objectInputStream.readObject();
    }
}
