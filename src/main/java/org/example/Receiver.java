package org.example;


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

/**
 * The server will be able to receive the file from the sender via UDP
 */
public class Receiver {
    enum State {
        CLOSED,
        LISTEN,
        ESTABLISHED,
        TIME_WAIT,
    }
    private static final int BUFFERSIZE = 1024;
    // change it to 0.0.0.0 or public ipv4 address if want to test it between different computers
    private final String address = "127.0.0.1";
    // receiver_port: the UDP port number to be used by the receiver to receive PTP segments from the sender.
    private final int receiverPort;
    private State state;
    private final Lock stpLock = new ReentrantLock();
    private final Condition receivedCondition = stpLock.newCondition();
    private STPSegment stp;
    private StringBuffer log;
    // the UDP port number to be used by the sender to send PTP segments to the receiver.
    private final int senderPort;
    // the name of the text file into which the text sent by the sender should be stored
    private final String filename;
    // forward loss probability, which is the probability that any segment in the forward direction (Data, FIN, SYN) is lost.
    private final float flp;
    // reverse loss probability, which is the probability of a segment in the reverse direction (i.e., ACKs) being lost.
    private final float rlp;

    private long startTime;
    private final InetAddress serverAddress;
    private int receivedDataBytesCounter = 0;
    private int receivedDataSegmentCounter = 0;
    private int duplicateDataSegmentsCounter = 0;
    private int droppedDataSegmentCounter = 0;
    private int droppedACKCounter = 0;
    private final DatagramSocket receiverSocket;
    private List<STPSegment> receivedTotal;
    // check duplicate and act as a linked list
    private HashMap<Integer, STPSegment> buffer;
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
        this.receiverSocket = new DatagramSocket(receiverPort, serverAddress);
        this.receivedTotal = new ArrayList<>();
        this.buffer = new HashMap<>();
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
            // drop by rlp
            logMessage("rdrp", System.nanoTime(), type, sequenceNumber, 0);
            return false;
        }
    }

    private void ptpOpen() throws IOException {
        while (this.state == State.LISTEN) {
            STPSegment stp = this.receive();
            if (Utils.dropPacket(flp, stp.getType())) {
                // drop by flp
                logMessage("fdrp", System.nanoTime(), stp.getType(), stp.getSeqNo(), 0);
                continue;
            }
            logMessage("rcv", System.nanoTime(), stp.getType(), stp.getSeqNo(), 0);
            if (stp.getType() == STPSegment.SYN) {
                while (!sendACKInRlp(stp.getSeqNo(), 1)) {

                }
                this.expectedSeq = Utils.seq(stp.getSeqNo() + 1);
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
            if (Utils.dropPacket(flp, currentType)) {
                // drop by flp
                logMessage("fdrp", System.nanoTime(), currentType, currentSeq, received.getPayloadLength());
                continue;
            }
            if (currentType == STPSegment.DATA) {
                handleDataSegment(received);
            }
            if (currentType == STPSegment.FIN) {
                logMessage("rcv", System.nanoTime(), currentType, currentSeq, received.getPayloadLength());
                this.state = State.TIME_WAIT;
                sendACKInRlp(currentSeq, 1);
                break;
            }
            if (currentType == STPSegment.RESET) {
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
                    // drop by flp
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

    private void handleDataSegment(STPSegment received) throws IOException {
        int currentSeq = received.getSeqNo();
        int currentType = received.getType();
        if (this.expectedSeq == currentSeq) {
            this.receivedTotal.add(received);
            this.buffer.put(currentSeq, received);
            logMessage("rcv", System.nanoTime(), currentType, currentSeq, received.getPayloadLength());
            this.expectedSeq = Utils.seq(currentSeq + received.getPayloadLength());
            if (!this.buffer.isEmpty()) {
                List<STPSegment> list = new ArrayList<>();
                STPSegment stp;
                while ((stp = buffer.get(this.expectedSeq)) != null) {
                    this.expectedSeq = Utils.seq(this.expectedSeq + stp.getPayloadLength());
                    list.add(stp);
                }
                this.receivedTotal.addAll(list);
            }
            sendACKInRlp(this.expectedSeq, 0);
        } else {
            if (this.buffer.get(currentSeq) == null) {
                this.buffer.put(currentSeq, received);
                logMessage("rcv", System.nanoTime(), currentType, currentSeq, received.getPayloadLength());
            } else {
                this.duplicateDataSegmentsCounter++;
                // drop due to duplicate
                logMessage("drp", System.nanoTime(), currentType, currentSeq, received.getPayloadLength());
            }
            sendACKInRlp(this.expectedSeq, 0);
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
                this.duplicateDataSegmentsCounter, this.droppedDataSegmentCounter,
                this.droppedACKCounter));

        FileOutputStream fos = new FileOutputStream(this.filename);
        List<STPSegment> uniqueList = this.receivedTotal.stream()
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
        if (type == STPSegment.ACK) {
            if (action.contains("drp")) {
                this.droppedACKCounter++;
            }
        }
        if (type == STPSegment.DATA) {
            if (action.contains("rcv")) {
                this.receivedDataSegmentCounter++;
                this.receivedDataBytesCounter += numberOfBytes;
            }
            if (action.contains("drp")) {
                this.droppedDataSegmentCounter++;
            }
        }

        newLog = String.format("%4s\t%10s\t%6s\t%5d\t%d%n", action, durationString, packetType, sequence, numberOfBytes);
        System.out.print(newLog);
        this.log.append(newLog);
    }

    public static void main(String[] args) throws IOException {
        Logger.getLogger(Receiver.class.getName()).log(Level.INFO, "Starting Receiver...");
        if (args.length == 0) {
            System.out.println("Args are empty. Use default configuration.");
            args = new String[]{"8888", "7777", "FileReceived.txt", "0.5", "0"}; // flp rlp
        }
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