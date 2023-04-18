package org.example.test4;

import java.io.*;
import java.net.*;

public class Receiver {
    public static void main(String[] args) throws IOException, ClassNotFoundException {
        args = new String[]{"./FileReceived.txt", "8888", "127.0.0.1"};
        if (args.length != 3) {
            System.out.println("Usage: java Receiver <output_file_path> <listen_port> <sender_ip>");
            System.exit(1);
        }

        String outputPath = args[0];
        int listenPort = Integer.parseInt(args[1]);
        String senderIp = args[2];

        // Create a socket for receiving data packets and sending acknowledgments
        DatagramSocket socket = new DatagramSocket(listenPort);

        // Create a file output stream to write the received data
        FileOutputStream fileOutputStream = new FileOutputStream(outputPath);
        int expectedSequenceNumber = 0;

        while (true) {
            // Receive a data packet
            byte[] receiveData = new byte[1024];
            DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
            socket.receive(receivePacket);
            Packet dataPacket = deserialize(receivePacket.getData(), receivePacket.getLength());

            // Check the sequence number and write the data to the output file
            if (dataPacket.getType() == Packet.Type.DATA && dataPacket.getSequenceNumber() == expectedSequenceNumber) {
                fileOutputStream.write(dataPacket.getData());

                // Increment the expected sequence number (mod 2) for the next packet
                expectedSequenceNumber = (expectedSequenceNumber + 1) % 2;
            }

            // Send an acknowledgment with the received sequence number
            Packet ackPacket = new Packet(Packet.Type.ACK, dataPacket.getSequenceNumber(), null);
            byte[] ackData = serialize(ackPacket);
            DatagramPacket ackSendPacket = new DatagramPacket(ackData, ackData.length, InetAddress.getByName(senderIp), receivePacket.getPort());
            socket.send(ackSendPacket);
        }
    }

    private static byte[] serialize(Packet packet) throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        ObjectOutputStream objectOutputStream = new ObjectOutputStream(byteArrayOutputStream);
        objectOutputStream.writeObject(packet);
        objectOutputStream.flush();
        return byteArrayOutputStream.toByteArray();
    }

    private static Packet deserialize(byte[] data, int length) throws IOException, ClassNotFoundException {
        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(data, 0, length);
        ObjectInputStream objectInputStream = new ObjectInputStream(byteArrayInputStream);
        return (Packet) objectInputStream.readObject();
    }

}
