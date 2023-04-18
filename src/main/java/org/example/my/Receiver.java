package org.example.my;

import java.io.*;
import java.net.*;

public class Receiver {
    static int MSS = 1024;
    public static void main(String[] args) throws IOException, ClassNotFoundException {
        int receiverPort = 8888;
        int senderPort = 7777;
        String fileReceived = "FileReceived.txt";
        // forward loss probability, which is the probability that any segment in the forward direction (Data, FIN, SYN) is lost.
        double flp = 0.1;
        // reverse loss probability, which is the probability of a segment in the reverse direction (i.e., ACKs) being lost.
        double rlp = 0.05;

//        STPSocket socket = new STPSocket(receiverPort);
        DatagramSocket socket = new DatagramSocket(receiverPort);
        byte[] buffer = new byte[MSS];

        // ack
        // fin
        while(true) {
            DatagramPacket response = new DatagramPacket(buffer, MSS);
            socket.receive(response);
            byte[] data = response.getData();
            Packet packet = deserialize(data);

//            short seqNo = packet.getSeqNo();
//            System.out.println("seqNo = " + seqNo);
        }
    }

    private static byte[] serialize(Packet packet) throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        ObjectOutputStream objectOutputStream = new ObjectOutputStream(byteArrayOutputStream);
        objectOutputStream.writeObject(packet);
        objectOutputStream.flush();
        objectOutputStream.close();
        return byteArrayOutputStream.toByteArray();
    }

    private static Packet deserialize(byte[] data) throws IOException, ClassNotFoundException {
        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(data);
        ObjectInputStream objectInputStream = new ObjectInputStream(byteArrayInputStream);
        return (Packet) objectInputStream.readObject();
    }
}
