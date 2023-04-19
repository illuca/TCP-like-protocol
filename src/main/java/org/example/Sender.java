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

import org.apache.commons.lang3.ArrayUtils;

import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.logging.*;
import java.util.concurrent.locks.ReentrantLock;



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

    private int ISN;
    private int expectedSeq;
    private final InetAddress receiverAddress;
    private final DatagramSocket senderSocket;

    private StringBuffer log = null;
    private State state = State.CLOSED;
    private final String filename;
    // must >= 1000. and should be n * 1000
    private final int maxWin;
    private final int rto;
    private STPSegment[] unacknowledgedSegments;
    private Set<Integer> sent;
    private int retransmissionCount = 0;
    private long startTime = 0;

    private int bytesSentCounter = 0;
    private int segmentSentCounter = 0;
    private int retransmittedSegmentCounter = 0;
    private int duplicatedACKCounter = 0;
    private final Lock lock = new ReentrantLock();

    private final int MSS = 1000;

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
        this.unacknowledgedSegments = new STPSegment[maxWin / MSS];
        this.sent = new HashSet<>();

        // init the UDP socket
        Logger.getLogger(Sender.class.getName()).log(Level.INFO, "The sender is using the address {0}:{1}", new Object[] { senderAddress, senderPort });
        this.senderSocket = new DatagramSocket(senderPort, senderAddress);
//        this.senderSocket.setSoTimeout(this.rto);
        // start the listening sub-thread

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
            this.ISN = 4521;
            sendFlagPacket(STPSegment.SYN, this.ISN);
            this.state = State.SYN_SENT;

            // try to get ack
            try {
                DatagramPacket incoming = receive();
                STPSegment incomingSegment = STPSegment.fromBytes(incoming.getData());

                int type = incomingSegment.getType();
                int seqNo = incomingSegment.getSeqNo();


                if (type == STPSegment.ACK && seqNo == Utils.seq(this.ISN + 1)) {
                    // receive action
                    this.ISN = seqNo;
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
        Thread listenThread = new Thread(this::listen);
        listenThread.start();

        System.out.println("ptpSend is called");
        // todo add codes here
        int bytesRead = 0;
        byte[] buffer = new byte[this.MSS];
        FileInputStream fis = new FileInputStream(this.filename);
        int seq = this.ISN;
        boolean flag = true;
        while (true) {
            while(true) {
                if (flag) {
                    bytesRead = fis.read(buffer);
                    long sentTime = System.nanoTime();
                    long expectedTime = sentTime + this.rto * 1000000L;
                    int expectedACK = Utils.seq(seq + bytesRead);
                    System.out.println("seq=" + seq);
                    STPSegment newStp = new STPSegment(STPSegment.DATA, seq, expectedACK, Arrays.copyOfRange(buffer, 0, bytesRead), sentTime, expectedTime);
                    System.out.println("newStp = " + newStp);
                    seq = expectedACK;
                    System.out.println("--");
                    printMyArray(this.unacknowledgedSegments);
                    System.out.println("--");
                    flag = addValue(this.unacknowledgedSegments, newStp);
                    System.out.println("flag = " + flag);
                }
                if (bytesRead == -1) {
                    break;
                }
                printMyArray(this.unacknowledgedSegments);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }

                if (!flag){
                    System.out.println("addValue failed.");
                }
                if (isFull(this.unacknowledgedSegments)) {
                    break;
                }
            }

            for (int i = 0; i < this.unacknowledgedSegments.length; i++) {
                System.out.println("for");
                STPSegment stp = this.unacknowledgedSegments[i];
                if (stp == null) {
                    continue;
                }
                byte[] data = stp.toBytes();
                DatagramPacket packet = new DatagramPacket(data, data.length, this.receiverAddress, this.receiverPort);
                System.out.println("this.sent=" + this.sent);
                if (!this.sent.contains(stp.getSeqNo())
//                        || isTimeout(stp)
                ) {
                    this.senderSocket.send(packet);
                    logMessage("snd", stp.getSendTime(), STPSegment.DATA, stp.getSeqNo(), bytesRead);
                    try {
                        lock.lock();
                        this.sent.add(stp.getSeqNo());
                    } finally {
                        lock.unlock();
                    }
                }
            }
            if(bytesRead == -1) {
                break;
            }
        }
        System.out.println("bytesRead=" + bytesRead);
        fis.close();
    }

    private boolean isArrayEmpty(STPSegment[] unacknowledgedSegments) {
        int counter=0;
        for (int i = 0; i < unacknowledgedSegments.length; i++) {
            if(unacknowledgedSegments[i]!=null) {
                counter++;
            }
        }
        return counter == 0;
    }

    private void printMyArray(STPSegment[] unacknowledgedSegments) {
        for (int i = 0; i < unacknowledgedSegments.length; i++) {
            if (unacknowledgedSegments[i] != null) {
                System.out.println("unacknowledgedSegment[i] = " + unacknowledgedSegments[i]);
            }
        }
    }

    private boolean addValue(STPSegment[] array, STPSegment stp) {
        for (int i = 0; i < array.length; i++) {
            if (array[i] == null) {
                array[i] = stp;
                return true;
            }
        }
        return false;
    }

    private boolean isFull(STPSegment[] array) {
        int counter = 0;
        for (int i = 0; i < array.length; i++) {
            if (array[i] != null) {
                counter++;
            }
        }
        return counter == array.length;
    }


    private boolean isTimeout(STPSegment stp) {
        return false;
//        if (System.nanoTime() > stp.getExpectedSeqNo()) {
//            return true;
//        }
//        return false;
    }

    public void ptpClose() throws IOException {
        // todo add codes here
        sendFlagPacket(STPSegment.FIN, Utils.seq(this.ISN + 1));
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

    public void listen() {
        System.out.println("listen is called");
        try {
            // listen to incoming packets from receiver
            while (this.state == State.ESTABLISHED) {
                DatagramPacket ack = receive();
                STPSegment ackSegment = STPSegment.fromBytes(ack.getData(), ack.getLength());
                int index = findACK(ackSegment.getSeqNo());
                if (index != -1) {
                    lock.lock(); // Acquire the lock
                    try {
                        this.unacknowledgedSegments[index] = null;
                    } finally {
                        lock.unlock(); // Release the lock
                    }
                }
            }
//            while (true) {
//                // try to get ack
//                System.out.println("this.state");
//                System.out.println(this.state);
//
//            }
        } catch(IOException e) {
            // error while listening, stop the thread
            Logger.getLogger(Sender.class.getName()).log(Level.SEVERE, "Error while listening", e);
        }
    }

    private int findACK(int ack) {
        for (int i = 0; i < this.unacknowledgedSegments.length; i++) {
            if (this.unacknowledgedSegments[i] != null) {
                if (this.unacknowledgedSegments[i].getExpectedACK() == ack) {
                    return i;
                }
            }
        }
        return -1;
    }

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

