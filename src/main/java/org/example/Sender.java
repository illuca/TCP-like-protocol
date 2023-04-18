package org.example; /**
    Sample code for Receiver
    Python 3
    Usage: 
        - You need to compile it first: javac Sender.java
        - then run it: java Sender 9000 10000 FileToReceived.txt 1000 1
    coding: utf-8

    Notes:
        Try to run the server first with the command:
            java Receiver 10000 9000 FileReceived.txt 1 1
        Then run the sender:
            java Sender 9000 10000 FileToReceived.txt 1000 1

    Author: Wei Song (Tutor for COMP3331/9331)
 */

import java.net.*;
import java.io.*;
import java.util.Arrays;
import java.util.Random;
import java.util.logging.*;




public class Sender {

    enum State {
        CLOSED,
        SYN_SENT,
        ESTABLISHED,
        CLOSING,
        FIN_WAIT,
    }
    /** 
        The Sender will be able to connect the Receiver via UDP
        :param sender_port: the UDP port number to be used by the sender to send PTP segments to the receiver
        :param receiver_port: the UDP port number on which receiver is expecting to receive PTP segments from the sender
        :param filename: the name of the text file that must be transferred from sender to receiver using your reliable transport protocol.
        :param max_win: the maximum window size in bytes for the sender window.
        :param rot: the value of the retransmission timer in milliseconds. This should be an unsigned integer.
    */

    private final int senderPort;
    private final int receiverPort;
    private final InetAddress senderAddress;

    private int currentSeq;
    private int expectedSeq;
    private final InetAddress receiverAddress;
    private final DatagramSocket senderSocket;

    private StringBuffer log = null;
    private State state = State.CLOSED;
    private final String filename;
    private final int maxWin;
    private final int rto;

    private int retransmissionCount = 0;
    private long startTime = 0;

    private int bytesSentCounter = 0;
    private int segmentSentCounter = 0;
    private int retransmittedSegmentCounter = 0;
    private int duplicatedACKCounter = 0;

    private final int BUFFERSIZE = 1004;

    public Sender(int senderPort, int receiverPort, String filename, int maxWin, int rto) throws IOException {
        this.senderPort = senderPort;
        this.receiverPort = receiverPort;
        this.senderAddress = InetAddress.getByName("127.0.0.1");
        this.receiverAddress = InetAddress.getByName("127.0.0.1");
        this.filename = filename;
        this.maxWin = maxWin;
        this.rto = rto;
        this.log = new StringBuffer();
        this.log.append(new String().format("<snd/rcv/drop> <time> <type of packet> <seq-number> <number-ofbytes> <ack-number>%n"));

        // init the UDP socket
        Logger.getLogger(Sender.class.getName()).log(Level.INFO, "The sender is using the address {0}:{1}", new Object[] { senderAddress, senderPort });
        this.senderSocket = new DatagramSocket(senderPort, senderAddress);
//        this.senderSocket.setSoTimeout(this.rto);
        // start the listening sub-thread
//        Thread listenThread = new Thread(this::listen);
//        listenThread.start();

        // todo add codes here
    }

    // pointer to pointer
    public void ptpOpen() throws IOException {
        // todo add/modify codes here
        // send a greeting message to receiver
        // initial sequence number is 0~2^16-1

        while ((this.state == State.CLOSED || this.state == State.SYN_SENT)
                && this.retransmissionCount < 3) {
            Random random = new Random();
//            this.currentSeq = random.nextInt(65536);
            this.currentSeq = 4521;
            this.sendFlagPacket(STPSegment.SYN, this.currentSeq);
            this.state = State.SYN_SENT;

            // try to get ack
            try {
                DatagramPacket incoming = this.receive();
                STPSegment incomingSegment = STPSegment.fromBytes(incoming.getData());

                int type = incomingSegment.getType();
                int seqNo = incomingSegment.getSeqNo();
                byte[] received = incomingSegment.getPayload();

                if (type == STPSegment.ACK && seqNo == Utils.seq(this.currentSeq + 1)) {
                    // receive action
                    this.currentSeq = seqNo;
                    this.state = State.ESTABLISHED;
                    System.out.println("Handshake succeed.");
                } else {
                    System.out.println("Handshake failed.");
                }
            } catch (SocketTimeoutException e) {
                this.retransmissionCount++;
                this.retransmittedSegmentCounter++;
                System.out.println("Timeout occurred, retransmission count: " + retransmissionCount);
            }

        }
        if (this.state != State.ESTABLISHED && this.retransmissionCount == 3) {
            // send reset to receiver
            sendFlagPacket(STPSegment.RESET, 0);
            this.reset();
        }
    }
    public void ptpSend() throws IOException {
        // todo add codes here
        while (true) {
            FileInputStream fis = new FileInputStream(this.filename);
            int bytesRead = 0;
            byte[] buffer = new byte[this.BUFFERSIZE];
            while ((bytesRead = fis.read(buffer)) != -1) {
                STPSegment stp = new STPSegment(STPSegment.DATA, this.currentSeq, Arrays.copyOfRange(buffer, 0, bytesRead));
                byte[] data = stp.toBytes();
                DatagramPacket packet = new DatagramPacket(data, data.length, this.receiverAddress, this.receiverPort);
                this.expectedSeq = Utils.seq(this.currentSeq + bytesRead);
                this.senderSocket.send(packet);
                logMessage("snd", System.nanoTime(), STPSegment.DATA, this.currentSeq, bytesRead);
            }

            DatagramPacket receivedPacket = this.receive();
            STPSegment receivedSegment = STPSegment.fromBytes(receivedPacket.getData(), receivedPacket.getLength());
            if (receivedSegment.getType() == STPSegment.ACK && receivedSegment.getSeqNo() == this.expectedSeq) {
                this.currentSeq = this.expectedSeq;
                logMessage("rcv", System.nanoTime(), STPSegment.ACK, receivedSegment.getSeqNo(), 0);
                fis.close();
                break;
            }
            fis.close();
        }
    }

    public void ptpClose() throws IOException {
        // todo add codes here
        sendFlagPacket(STPSegment.FIN, Utils.seq(this.currentSeq + 1));
        this.state = State.FIN_WAIT;

        this.log.append(String.format("%d\t%d\t%d\t%d%n",
                this.bytesSentCounter, this.segmentSentCounter,
                this.retransmittedSegmentCounter, this.duplicatedACKCounter));
        senderSocket.close();
    }

    private void reset() {
        this.retransmissionCount = 0;
        this.state = State.CLOSED;
    }



    public void sendFlagPacket(int type, int sequenceNumber) throws IOException {
        STPSegment stp = new STPSegment(type, sequenceNumber, null);
        byte[] data = stp.toBytes();
        DatagramPacket packet = new DatagramPacket(data, data.length, this.receiverAddress, this.receiverPort);
        senderSocket.send(packet);
        this.logMessage("snd", System.nanoTime(), type, sequenceNumber, 0);
    }
    private void logMessage(String action, long currentTime, int type, int sequence, int numberOfBytes) {
        String newLog;
        String packetType = Utils.getStringType(type);

        if (type == STPSegment.SYN) {
            this.startTime = currentTime;
        }
        long duration = currentTime - this.startTime;

        if ("SYN".equals(packetType)) {
            newLog = String.format("%4s\t%10d\t%6s\t%5d\t%d%n", action, duration, packetType, sequence, numberOfBytes);
        } else {
            double time = (double) duration / 1000000;
            newLog = String.format("%4s\t%10.2f\t%6s\t%5d\t%d%n", action, time, packetType, sequence, numberOfBytes);
        }
        System.out.print(newLog);
        this.bytesSentCounter += numberOfBytes;
        if (type == STPSegment.DATA) {
            this.segmentSentCounter++;
        }
        this.log.append(newLog);
    }

    private DatagramPacket receive() throws IOException {
        byte[] buffer = new byte[4];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length, receiverAddress, receiverPort);
        this.senderSocket.receive(packet);
        STPSegment incomingSegment = STPSegment.fromBytes(packet.getData());
        logMessage("rcv", System.nanoTime(), incomingSegment.getType(), incomingSegment.getSeqNo(), packet.getLength() - 4);
        return packet;
    }

//    public void listen() {
//        byte[] receiveData = new byte[BUFFERSIZE];
//        DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
//        try {
//            // listen to incoming packets from receiver
//            while (true) {
//                // try to get ack
//                senderSocket.receive(receivePacket);
//                Logger.getLogger(Sender.class.getName()).log(Level.INFO, "received reply from receiver: {0}", incomingMessage);
//            }
//        } catch (IOException e) {
//            // error while listening, stop the thread
//            Logger.getLogger(Sender.class.getName()).log(Level.SEVERE, "Error while listening", e);
//        }
//    }

    public void run() throws IOException {
        // todo add/modify codes here
        ptpOpen();

        if (this.state == State.ESTABLISHED) {
            ptpSend();
            this.state = State.CLOSING;
            ptpClose();
        }
    }

    public static void main(String[] args) throws IOException {
        args = new String[]{"7777", "8888", "FileToSend.txt", "1000", "20"};
        Logger.getLogger(Sender.class.getName()).setLevel(Level.ALL);

        if (args.length != 5) {
            System.err.println("\n===== Error usage, java Sender senderPort receiverPort FileReceived.txt maxWin rto ======\n");
            System.exit(0);
        }

        Sender sender = new Sender(Integer.parseInt(args[0]), Integer.parseInt(args[1]), args[2], Integer.parseInt(args[3]), Integer.parseInt(args[4]));
        sender.run();
    }
}

