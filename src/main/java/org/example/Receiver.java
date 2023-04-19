package org.example; /**
    Sample code for Receiver
    Java
    Usage: 
        - You need to compile it first: javac Receiver.java
        - then run it: java Receiver receiver_port sender_port FileReceived.txt flp rlp
    coding: utf-8

    Notes:
        Try to run the server first with the command:
            java Receiver 10000 9000 FileReceived.txt 1 1
        Then run the sender:
            java Sender 9000 10000 FileToReceived.txt 1000 1

    Author: Wei Song (Tutor for COMP3331/9331)    
 */


import org.apache.commons.lang3.ArrayUtils;

import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;



public class Receiver {
    enum State {
        CLOSED,
        LISTEN,
        ESTABLISHED,
        TIME_WAIT,
    }
    /**
    The server will be able to receive the file from the sender via UDP
    :param receiver_port: the UDP port number to be used by the receiver to receive PTP segments from the sender.
    :param sender_port: the UDP port number to be used by the sender to send PTP segments to the receiver.
    :param filename: the name of the text file into which the text sent by the sender should be stored
    :param flp: forward loss probability, which is the probability that any segment in the forward direction (Data, FIN, SYN) is lost.
    :param rlp: reverse loss probability, which is the probability of a segment in the reverse direction (i.e., ACKs) being lost.
     */

    private static final int BUFFERSIZE = 1024;
    private final String address = "127.0.0.1"; // change it to 0.0.0.0 or public ipv4 address if want to test it between different computers
    private final int receiverPort;
    private State state;
    private StringBuffer log = null;
    private final int senderPort;
    private final String filename;
    private final float flp;
    private final float rlp;

    private long startTime;
    private final InetAddress serverAddress;
    private int receivedDataBytesCounter = 0;
    private int receivedDataSegmentCounter = 0;
    private int duplicateSegmentCounter = 0;
    private int droppedDataSegmentCounter = 0;
    private int droppedACKCounter = 0;

    private final DatagramSocket receiverSocket;

    // my data
    private int max_win;

    private InetAddress senderAddress;

    public Receiver(int receiverPort, int senderPort, String filename, float flp, float rlp) throws IOException {
        this.receiverPort = receiverPort;
        this.senderPort = senderPort;
        this.filename = filename;
        this.flp = flp;
        this.rlp = rlp;
        this.log = new StringBuffer();
        this.serverAddress = InetAddress.getByName(address);

        // init the UDP socket
        // define socket for the server side and bind address
        Logger.getLogger(Receiver.class.getName()).log(Level.INFO, "The sender is using the address " + serverAddress + " to receive message!");
        this.receiverSocket = new DatagramSocket(receiverPort, serverAddress);

        this.state = State.LISTEN;
    }

    public boolean sendFlagPacketInRlp(int type, int sequenceNumber, InetAddress senderAddress) throws IOException {
        STPSegment stp = new STPSegment(type, sequenceNumber,null);
        byte[] data = stp.toBytes();
        DatagramPacket packet = new DatagramPacket(data, data.length, senderAddress, this.senderPort);
        if(Utils.shouldSendPacket(this.rlp)) {
            this.receiverSocket.send(packet);
            logMessage("snd", System.nanoTime(), type, sequenceNumber, 0);
            return true;
        } else {
            logMessage("snd", System.nanoTime(), type, sequenceNumber, 0);
            this.droppedACKCounter++;
            return false;
        }
    }

    public void run() throws IOException {
        ptpOpen();
        ptpReceiveData();
        if (this.state == State.TIME_WAIT) {
            // 2 * life
        }
        ptpClose();
    }

    private void ptpClose() {
        // write log
        this.log.append(String.format("%d\t%d\t%d\t%d\t%d%n",
                this.receivedDataBytesCounter, this.receivedDataSegmentCounter,
                this.duplicateSegmentCounter, this.droppedDataSegmentCounter,
                this.droppedACKCounter));
        this.receiverSocket.close();
    }

    private void ptpReceiveData() throws IOException {
        FileOutputStream fos = new FileOutputStream(this.filename);
        while(this.state == State.ESTABLISHED) {
            STPSegment received = this.receive();
            if (!Utils.shouldProcessPacket(flp)) {
                this.droppedDataSegmentCounter++;
                continue;
            }
            int currentSeq = received.getSeqNo();
            int currentType = received.getType();
            byte[] currentReceived = received.getPayload();

            if (currentType == STPSegment.DATA) {
                this.receivedDataSegmentCounter++;
                this.receivedDataBytesCounter += ArrayUtils.getLength(currentReceived);
                fos.write(currentReceived);
                fos.flush();
                sendACK(currentSeq, ArrayUtils.getLength(currentReceived));
            }
            if (currentType == STPSegment.FIN) {
                this.state = State.TIME_WAIT;
                sendACK(currentSeq, 1);
            }
        }
        fos.close();
    }

    private void ptpOpen() throws IOException {
        while(this.state == State.LISTEN) {
            STPSegment received = this.receive();
            if (!Utils.shouldProcessPacket(flp)) {
                continue;
            }
            logMessage("rcv", System.nanoTime(), received.getType(), received.getSeqNo(), 0);
            if (received.getType() == STPSegment.SYN) {
                if(sendACK(received.getSeqNo(), 1)) {
                    this.state = State.ESTABLISHED;
                }
            }
            if (received.getType() == STPSegment.RESET) {
                this.state = State.CLOSED;
            }
        }
    }
    public STPSegment receive() throws IOException {
        byte[] buffer = new byte[BUFFERSIZE];
        DatagramPacket incomingPacket = new DatagramPacket(buffer, buffer.length);

        this.receiverSocket.receive(incomingPacket);
        this.senderAddress = incomingPacket.getAddress();
        byte[] realData = Arrays.copyOfRange(incomingPacket.getData(), 0, incomingPacket.getLength());
        STPSegment incomingSegment = STPSegment.fromBytes(realData, incomingPacket.getLength());
        return incomingSegment;
    }
    private void handleFile(int currentSeq, int currentType, byte[] currentReceived, FileOutputStream fos) throws IOException {
        if (currentType == STPSegment.DATA) {
            this.receivedDataSegmentCounter++;
            this.receivedDataBytesCounter += ArrayUtils.getLength(currentReceived);
            fos.write(currentReceived);
            fos.flush();
            sendACK(currentSeq, ArrayUtils.getLength(currentReceived));
        }
    }

    private boolean sendACK(int currentSeq, int length) throws IOException {
        int seqToSend = Utils.seq(currentSeq + length);
        return sendFlagPacketInRlp(STPSegment.ACK, seqToSend, this.senderAddress);
    }

    private void logMessage(String action, long currentTime, int type, int sequence, int numberOfBytes) {
        String packetType = Utils.getStringType(type);
        String newLog;

        if (type == STPSegment.SYN) {
            this.startTime = currentTime;
        }

        long duration = currentTime - this.startTime;
        if (type == STPSegment.SYN) {
            newLog = String.format("%4s\t%10d\t%10s\t%5d\t%d%n", action, duration, packetType, sequence, numberOfBytes);
        } else {
            double time = (double) duration / 1000000;
            newLog = String.format("%4s\t%10.2f\t%10s\t%5d\t%d%n", action, time, packetType, sequence, numberOfBytes);
        }
        System.out.print(newLog);
        this.log.append(newLog);
    }

    public static void main(String[] args) throws IOException {
        Logger.getLogger(Receiver.class.getName()).log(Level.INFO, "Starting Receiver...");
        args = new String[]{"8888", "7777", "FileReceived.txt", "0", "0"};
        if (args.length != 5) {
            System.err.println("\n===== Error usage, java Receiver <receiver_port> <sender_port> <FileReceived.txt> <flp> <rlp> =====\n");
            return;
        }

        int receiverPort = Integer.parseInt(args[0]);
        int senderPort = Integer.parseInt(args[1]);
        String filename = args[2];
        float flp = Float.parseFloat(args[3]);
        float rlp = Float.parseFloat(args[4]);

        Receiver receiver = new Receiver(receiverPort, senderPort, filename, flp, rlp);
        receiver.run();
    }
}