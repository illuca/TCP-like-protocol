package org.example.test2;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;

public class STPSocket {


    int port;
    private DatagramSocket datagramSocket;

    public STPSocket(int port) throws SocketException {
        this.port = port;
        datagramSocket = new DatagramSocket(port);
    }

    void send(STPPacket stpPacket) throws IOException {
        datagramSocket.send(stpPacket.getDatagramPacket());
    }


    public void receive(STPPacket response) throws IOException {
        byte[] buffer = new byte[2];
        DatagramPacket datagramPacket = new DatagramPacket(buffer, 2);
        this.datagramSocket.receive(datagramPacket);
        response.setDatagramPacket(datagramPacket);
    }
}
