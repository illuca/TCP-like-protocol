package org.example.my;

import java.io.*;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

public class Sender {
    public static void main(String[] args) throws IOException {
        int senderPort = 7777;
        InetAddress receiverHost = InetAddress.getByName("127.0.0.1");
        int receiverPort = 8888;
        int MSS = 1024;
        String filePath = "./FileToSend.txt";
//        max_win must be greater than or equal to 1000 bytes (MSS) and does not include STP headers.
//        When max_win is set to 1000 bytes, STP will effectively behave as a stop-and-wait protocol,
//        wherein the sender transmits one data segment at any given time and waits for the
//        corresponding ACK segment. While testing, we will ensure that max_win is a multiple of
//        1000 bytes (e.g., 5000 bytes).
        int maxWin = 1000;
        // the value of the retransmission timer in milliseconds. This should be an unsigned integer.
        int rto = 2000;
        DatagramSocket socket = new DatagramSocket();
        byte[] fileBuffer = new byte[MSS];
        FileInputStream fileInputStream = new FileInputStream(filePath);
        int bytesRead;

        // send a file
        short type = 2;
        short seqNo = 4;
        while ((bytesRead = fileInputStream.read(fileBuffer)) != -1) {
            Packet segment = new Packet(type, seqNo, fileBuffer);

            byte[] sendData = serialize(segment);

            DatagramPacket packet = new DatagramPacket(sendData, bytesRead, receiverHost, receiverPort);
            socket.send(packet);
        }
        fileInputStream.close();
        socket.close();
    }

    private static byte[] serialize(Packet packet) throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        ObjectOutputStream objectOutputStream = new ObjectOutputStream(byteArrayOutputStream);
        objectOutputStream.writeObject(packet);
        objectOutputStream.flush();
        return byteArrayOutputStream.toByteArray();
    }

    private static Packet deserialize(byte[] data) throws IOException, ClassNotFoundException {
        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(data);
        ObjectInputStream objectInputStream = new ObjectInputStream(byteArrayInputStream);
        return (Packet) objectInputStream.readObject();
    }
}