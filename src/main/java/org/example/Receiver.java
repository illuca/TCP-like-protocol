package org.example; /**
 * Sample code for Receiver
 * Java
 * Usage:
 * - You need to compile it first: javac Receiver.java
 * - then run it: java Receiver receiver_port sender_port FileReceived.txt flp rlp
 * coding: utf-8
 * <p>
 * Notes:
 * Try to run the server first with the command:
 * java Receiver 10000 9000 FileReceived.txt 1 1
 * Then run the sender:
 * java Sender 9000 10000 FileToReceived.txt 1000 1
 * <p>
 * Author: Wei Song (Tutor for COMP3331/9331)
 */




import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.util.Comparator.comparingInt;
import static java.util.stream.Collectors.collectingAndThen;
import static java.util.stream.Collectors.toCollection;


public class Receiver {
    enum State {
        CLOSED,
        LISTEN,
        ESTABLISHED,
        TIME_WAIT,
    }

    /**
     * The server will be able to receive the file from the sender via UDP
     * :param receiver_port: the UDP port number to be used by the receiver to receive PTP segments from the sender.
     * :param sender_port: the UDP port number to be used by the sender to send PTP segments to the receiver.
     * :param filename: the name of the text file into which the text sent by the sender should be stored
     * :param flp: forward loss probability, which is the probability that any segment in the forward direction (Data, FIN, SYN) is lost.
     * :param rlp: reverse loss probability, which is the probability of a segment in the reverse direction (i.e., ACKs) being lost.
     */

    private static final int BUFFERSIZE = 1024;
    private final String address = "127.0.0.1"; // change it to 0.0.0.0 or public ipv4 address if want to test it between different computers
    private final int receiverPort;
    private State state;
    private final Lock stpLock = new ReentrantLock();
    private final Condition receivedCondition = stpLock.newCondition();
    private STPSegment stp;
    private Set<Integer> dataSeqSet;

    private StringBuffer log;
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
    private List<STPSegment> receiveWindow;
    private Thread FINThread;

    private int expectedSeq;

    private InetAddress senderAddress;

    public Receiver(int receiverPort, int senderPort, String filename, float flp, float rlp) throws IOException {
        this.receiverPort = receiverPort;
        this.senderPort = senderPort;
        this.filename = filename;
        this.flp = flp;
        this.rlp = rlp;
        this.log = new StringBuffer();
        this.serverAddress = InetAddress.getByName(address);
        this.startTime = System.nanoTime();
        this.dataSeqSet = new HashSet<>();

        // init the UDP socket
        // define socket for the server side and bind address
        Logger.getLogger(Receiver.class.getName()).log(Level.INFO, "The sender is using the address " + serverAddress + " to receive message!");
        this.receiverSocket = new DatagramSocket(receiverPort, serverAddress);
        this.receiveWindow = new ArrayList<>();

        this.state = State.LISTEN;


    }

    private boolean sendFlagPacketInRlp(int type, int sequenceNumber, InetAddress senderAddress) throws IOException {
        STPSegment stp = new STPSegment(type, sequenceNumber, null);
        byte[] data = stp.toBytes();
        DatagramPacket packet = new DatagramPacket(data, data.length, senderAddress, this.senderPort);
        // the receiver log will also record any dropped segments as drp.
        if (Utils.shouldSendPacket(this.rlp) || type == STPSegment.RESET) {
            logMessage("snd", System.nanoTime(), type, sequenceNumber, 0);
            this.receiverSocket.send(packet);
            return true;
        } else {
            logMessage("rdrp", System.nanoTime(), type, sequenceNumber, 0);
            this.droppedACKCounter++;
            return false;
        }
    }

    private void ptpOpen() throws IOException {
        while (this.state == State.LISTEN) {
            STPSegment stp = this.receive();
            if (Utils.dropPacket(flp, stp.getType())) {
                logMessage("fdrp", System.nanoTime(), stp.getType(), stp.getSeqNo(), 0);
                continue;
            }
            logMessage("rcv", System.nanoTime(), stp.getType(), stp.getSeqNo(), 0);
            if (stp.getType() == STPSegment.SYN) {
                while (!sendACKInRlp(stp.getSeqNo(), 1)) {

                }
                this.expectedSeq = stp.getSeqNo() + 1;
                this.state = State.ESTABLISHED;
            }
            if (stp.getType() == STPSegment.RESET) {
                this.state = State.CLOSED;
            }
        }
    }

    private void ptpReceive() throws IOException {
        while (this.state == State.ESTABLISHED) {
            STPSegment received = this.receive();
            int currentSeq = received.getSeqNo();
            int currentType = received.getType();
            byte[] currentReceived = received.getPayload();
            if (currentType == STPSegment.DATA) {
                if (dataSeqSet.contains(currentSeq)) {
                    this.duplicateSegmentCounter++;
                } else {
                    this.receivedDataSegmentCounter++;
                    this.receivedDataBytesCounter += Utils.getLength(currentReceived);
                    this.dataSeqSet.add(currentSeq);
                }
            }

            if (Utils.dropPacket(flp, currentType)) {
                logMessage("fdrp", System.nanoTime(), currentType, currentSeq, Utils.getLength(currentReceived));
                this.droppedDataSegmentCounter++;
                continue;
            }


            if (currentType == STPSegment.DATA) {
                if (this.expectedSeq == currentSeq && currentSeq != 4722) {
                    this.receiveWindow.add(received);
                    logMessage("rcv", System.nanoTime(), currentType, currentSeq, Utils.getLength(currentReceived));
                    sendACKInRlp(currentSeq, Utils.getLength(currentReceived));
                } else {
                    logMessage("drp", System.nanoTime(), currentType, currentSeq, Utils.getLength(currentReceived));
                    sendACKInRlp(this.expectedSeq, 0);
                }
            }
            if (currentType == STPSegment.FIN) {
                logMessage("rcv", System.nanoTime(), currentType, currentSeq, Utils.getLength(currentReceived));
                this.state = State.TIME_WAIT;
                sendACKInRlp(currentSeq, 1);
                break;
            }
            if (currentType == STPSegment.RESET) {
                logMessage("rcv", System.nanoTime(), currentType, currentSeq, Utils.getLength(currentReceived));
                logMessage("rcv", System.nanoTime(), currentType, currentSeq, 0);
                this.state = State.CLOSED;
                break;
            }
        }
        this.FINThread = new Thread(() -> {
            while (this.state == State.TIME_WAIT) {
                STPSegment stp = null;
                try {
                    stp = receive();
                } catch (IOException ignore) {
                }
                try {
                    this.stpLock.lock();
                    this.stp = stp;
                    receivedCondition.signal();
                } finally {
                    this.stpLock.unlock();
                }
            }
        });
        this.FINThread.start();

        while (this.state == State.TIME_WAIT) {
            boolean received = false;
            try {
                this.stpLock.lock();
                received = receivedCondition.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } finally {
                this.stpLock.unlock();
            }
            if (received) {
                if (Utils.dropPacket(flp, stp.getType())) {
                    logMessage("fdrp", System.nanoTime(), stp.getType(), stp.getSeqNo(), Utils.getLength(stp.getPayload()));
                    continue;
                }
                if (this.stp.getType() == STPSegment.FIN) {
                    logMessage("rcv", System.nanoTime(), stp.getType(), stp.getSeqNo(), Utils.getLength(stp.getPayload()));
                    sendACKInRlp(stp.getSeqNo(), 1);
                    continue;
                }
                if (this.stp.getType() == STPSegment.RESET) {
                    logMessage("rcv", System.nanoTime(), stp.getType(), stp.getSeqNo(), Utils.getLength(stp.getPayload()));
                    this.state = State.CLOSED;
                    break;
                }
            } else {
                this.state = State.CLOSED;
                break;
            }
        }
    }

    private void ptpClose() throws IOException {
        // write log
        this.log.append(String.format("Amount of (original) Data Received (in bytes): %d\n" +
                        "Number of (original) Data Segments Received: %d\n" +
                        "Number of duplicate Data segments received: %d\n" +
                        "Number of Data segments dropped: %d\n" +
                        "Number of ACK segments dropped: %d%n",
                this.receivedDataBytesCounter, this.receivedDataSegmentCounter,
                this.duplicateSegmentCounter, this.droppedDataSegmentCounter,
                this.droppedACKCounter));

        FileOutputStream fos = new FileOutputStream(this.filename);
        List<STPSegment> uniqueList = this.receiveWindow.stream()
                .collect(collectingAndThen(toCollection(() -> new TreeSet<>(comparingInt(STPSegment::getSeqNo))),
                        ArrayList::new));
        for (STPSegment segment : uniqueList) {
            fos.write(segment.getPayload());
        }
        fos.close();

        if (this.state == State.CLOSED) {
            this.receiverSocket.close();
            Utils.writeLogToFile(this.log, "Receiver_log.txt");
        }
    }

    public void run() throws IOException {
        ptpOpen();
        ptpReceive();
        ptpClose();
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

    private boolean sendACKInRlp(int currentSeq, int length) throws IOException {
        int seqToSend = Utils.seq(currentSeq + length);
        return sendFlagPacketInRlp(STPSegment.ACK, seqToSend, this.senderAddress);
    }

    private void logMessage(String action, long currentTime, int type, int sequence, int numberOfBytes) {
        String packetType = Utils.getStringType(type);
        String newLog;
        String durationString;

        if (type == STPSegment.RESET) {
            System.out.println("Connection is reset.");

        }
        if (type == STPSegment.SYN) {
            this.startTime = currentTime;
            durationString = "0";
        } else {
            durationString = Utils.durationString(currentTime, this.startTime);
        }

        newLog = String.format("%4s\t%10s\t%10s\t%5d\t%d%n", action, durationString, packetType, sequence, numberOfBytes);
        System.out.print(newLog);
        this.log.append(newLog);
    }

    public static void main(String[] args) throws IOException {
        Logger.getLogger(Receiver.class.getName()).log(Level.INFO, "Starting Receiver...");
//        56007 59606 FileToReceive.txt 0 0
        args = new String[]{"8888", "7777", "FileReceived.txt", "0.5", "0.5"}; // flp rlp
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