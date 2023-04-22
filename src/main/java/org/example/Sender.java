package org.example; /**
 * Sample code for Receiver
 * Python 3
 * Usage:
 * - You need to compile it first: javac Sender.java
 * - then run it: java Sender 9000 10000 FileToReceived.txt 1000 1
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

import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
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
     * The Sender will be able to connect the Receiver via UDP
     * :param sender_port: the UDP port number to be used by the sender to send PTP segments to the receiver
     * :param receiver_port: the UDP port number on which receiver is expecting to receive PTP segments from the sender
     * :param filename: the name of the text file that must be transferred from sender to receiver using your reliable transport protocol.
     * :param max_win: the maximum window size in bytes for the sender window.
     * :param rot: the value of the retransmission timer in milliseconds. This should be an unsigned integer.
     */

    private final int senderPort;
    private final int receiverPort;
    private final InetAddress senderAddress;

    private int ISN;
    private int lastDataSequence;
    private final InetAddress receiverAddress;
    private final DatagramSocket senderSocket;
    private final Lock socketLock = new ReentrantLock();
    private final StringBuffer log;
    private State state = State.CLOSED;
    private final String filename;
    // must >= 1000. and should be n * 1000
    private final int maxWin;
    private final int rto;
    private final BlockingQueue<STPSegment> unAcked;
    private Set<Integer> sent;

    private long startTime = 0;

    private int bytesSentCounter = 0;
    private int segmentSentCounter = 0;
    private int retransmittedDataSegmentsCounter = 0;
    private int duplicatedACKCounter = 0;
    private final Lock stateLock = new ReentrantLock();

    private DatagramPacket ack;
    private final Lock ackLock = new ReentrantLock();
    private final Condition ackReceivedCondition = ackLock.newCondition();
    private Thread receiveThread;
    private Thread sendFileThread;
    private final Condition stateCondition = stateLock.newCondition();


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
        this.unAcked = new LinkedBlockingQueue<>(maxWin / MSS);
        this.sent = ConcurrentHashMap.newKeySet();
//        this.receiveThread = new Thread(this::listen);
        // init the UDP socket
        Logger.getLogger(Sender.class.getName()).log(Level.INFO, "The sender is using the address {0}:{1}", new Object[]{senderAddress, senderPort});
        this.senderSocket = new DatagramSocket(senderPort, senderAddress);

        this.receiveThread = new Thread(() -> {

            while (this.state != State.SYN_SENT) {
                try {
                    stateLock.lock();
                    stateCondition.await();
                } catch (InterruptedException ignore) {
                } finally {
                    stateLock.unlock();
                }
            }
            while (this.state != State.CLOSED) {
                this.receiveACK();
            }
        });
        this.sendFileThread = new Thread(() -> {
            while (this.state != State.ESTABLISHED) {
                try {
                    stateLock.lock();
                    stateCondition.await();
                } catch (InterruptedException ignore) {
                } finally {
                    stateLock.unlock();
                }
            }
            while (this.state == State.ESTABLISHED || this.state == State.CLOSING) {
                sendFile();
            }
        });
    }

    // pointer to pointer
    public void ptpOpen() {
        setState(State.SYN_SENT);
        this.receiveThread.start();

        int counter = 0;
        while (this.state == State.SYN_SENT && counter < 3) {
            try {
                ackLock.lock();
                Random random = new Random();
//                this.ISN = random.nextInt(65536);
                this.ISN = 4721;
                sendFlagPacket(STPSegment.SYN, this.ISN);
                boolean ackReceived = ackReceivedCondition.await(this.rto, TimeUnit.MILLISECONDS);
                if (ackReceived) {
                    STPSegment ackSegment = STPSegment.fromBytes(this.ack.getData(), this.ack.getLength());
                    if (ackSegment.getType() == STPSegment.ACK && ackSegment.getSeqNo() == Utils.seq(this.ISN + 1)) {
                        setState(State.ESTABLISHED);
                        Logger.getLogger(Sender.class.getName()).log(Level.INFO, "Handshake succeed.");
                        break;
                    } else {
                        Logger.getLogger(Sender.class.getName()).log(Level.INFO, "Closed failed.");
                    }
                } else {
                    counter++;
                }
            } catch (InterruptedException e) {
            } finally {
                ackLock.unlock();
            }
        }
        if (this.state != State.ESTABLISHED && counter == 3) {
            sendFlagPacket(STPSegment.RESET, 0);
            this.doCloseConnection();
        }
    }

    public void ptpSend() throws IOException {
        this.sendFileThread.start();
        int bytesRead = 0;
        byte[] buffer = new byte[this.MSS];
        FileInputStream fis = new FileInputStream(this.filename);
        int seq = Utils.seq(this.ISN + 1);

        boolean isFileFinished = false;

        while (!isFileFinished) {
            while (this.unAcked.remainingCapacity() > 0) {
                bytesRead = fis.read(buffer);
                if (bytesRead == -1) {
                    isFileFinished = true;
                    this.lastDataSequence = seq;
                    break;
                }
                double sentTime = Utils.duration(System.nanoTime(), this.startTime);
                double expectedTime = sentTime + this.rto;
                int expectedACK = Utils.seq(seq + bytesRead);
                STPSegment newStp = new STPSegment(STPSegment.DATA, seq, expectedACK, Arrays.copyOfRange(buffer, 0, bytesRead), sentTime, expectedTime);
                this.unAcked.offer(newStp);
                seq = expectedACK;
            }
        }
        fis.close();
    }

    public void sendFile() {
        for (STPSegment stp : this.unAcked) {
            byte[] data = stp.toBytes();
            DatagramPacket packet = new DatagramPacket(data, data.length, this.receiverAddress, this.receiverPort);
            if (!this.sent.contains(stp.getSeqNo()) || Utils.isTimeout(stp, this.startTime)) {
                double sendTime = Utils.duration(System.nanoTime(), this.startTime);
                stp.setSendTime(sendTime);
                stp.setACKTime(sendTime + rto);
                if (this.sent.contains(stp.getSeqNo())) {
                    this.retransmittedDataSegmentsCounter++;
                } else {
                    this.segmentSentCounter++;
                    this.bytesSentCounter += stp.getPayloadLength();
                }
                logMessage("snd", stp.getSendTime(), STPSegment.DATA, stp.getSeqNo(), stp.getPayloadLength());
                try {
                    this.senderSocket.send(packet);
                } catch (IOException ignore) {
                }
                this.sent.add(stp.getSeqNo());
            }
        }
    }

    public void ptpClose() {
//        When the sending application (sitting above STP) is finished generating data,
//        it issues a "close" operation to STP. This causes the sender to enter the CLOSING state.
//        At this point, the sender must still ensure that any buffered data arrives at the receiver reliably.
        setState(State.CLOSING);

        while (this.unAcked.size() != 0) {}

        setState(State.FIN_WAIT);

        int counter = 0;
        while (this.state == State.FIN_WAIT && counter < 3) {
            try {
                ackLock.lock();
                sendFlagPacket(STPSegment.FIN, this.lastDataSequence);
                boolean ackReceived = ackReceivedCondition.await(this.rto, TimeUnit.MILLISECONDS);
                if (ackReceived) {
                    STPSegment segment = STPSegment.fromBytes(this.ack.getData(), this.ack.getLength());
                    if (segment.getType() == STPSegment.ACK && segment.getSeqNo() == Utils.seq(this.lastDataSequence + 1)) {
                        setState(State.CLOSED);
                        printLog("Closed succeed.");
                        break;
                    } else {
                        printLog("Closed failed.");
                    }
                } else {
                    counter++;
                    this.retransmittedDataSegmentsCounter++;
                }
            } catch (InterruptedException e) {
            } finally {
                ackLock.unlock();
            }
        }
        if (this.state == State.FIN_WAIT && counter == 3) {
            sendFlagPacket(STPSegment.RESET, 0);
            this.doCloseConnection();
        }
        if (this.state == State.CLOSED) {
            doCloseConnection();
        }
    }

    public void printLog(String message) {
        Logger.getLogger(Sender.class.getName()).log(Level.INFO, message);
    }

    private void doCloseConnection() {
        try {
            socketLock.lock();
            this.senderSocket.close();
        } finally {
            socketLock.unlock();
        }
        setState(State.CLOSED);
    }

    public void setState(State state) {
        try {
            stateLock.lock();
            this.state = state;
            this.stateCondition.signalAll();
        } finally {
            stateLock.unlock();
        }
    }

    public void sendFlagPacket(int type, int sequenceNumber) {
        STPSegment stp = new STPSegment(type, sequenceNumber, null);
        byte[] data = stp.toBytes();
        DatagramPacket packet = new DatagramPacket(data, data.length, this.receiverAddress, this.receiverPort);
        double sentTime = Utils.duration(System.nanoTime(), this.startTime);
        this.logMessage("snd", sentTime, type, sequenceNumber, 0);
        try {
            senderSocket.send(packet);
        } catch (IOException ignore) {
        }
    }

    private void logMessage(String action, double sentTime, int type, int sequence, int numberOfBytes) {
        String newLog;
        String packetType = Utils.getStringType(type);

        if (type == STPSegment.SYN) {
            this.startTime = System.nanoTime();
            newLog = String.format("%4s\t%10d\t%6s\t%5d\t%d%n", action, 0, packetType, sequence, numberOfBytes);
        } else {
            newLog = String.format("%4s\t%10.2f\t%6s\t%5d\t%d%n", action, sentTime, packetType, sequence, numberOfBytes);
        }
        System.out.print(newLog);
        this.log.append(newLog);
        this.bytesSentCounter += numberOfBytes;
        if (type == STPSegment.DATA) {
            this.segmentSentCounter++;
        }
    }

    public void removeBeforeAndIncluding(int x) {
        synchronized (this.unAcked) {
            AtomicBoolean found = new AtomicBoolean(false);
            this.unAcked.removeIf(segment -> {
                if (!found.get()) {
                    if (segment.getExpectedACK() == x) {
                        found.set(true);
                    }
                    return true;
                }
                return false;
            });
        }
    }

    public void receiveACK() {
        byte[] buffer = new byte[4];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length, receiverAddress, receiverPort);
        try {
            this.senderSocket.receive(packet);
        } catch (IOException ignore) {
        }
        STPSegment ackSegment = STPSegment.fromBytes(packet.getData());
        if (this.state == State.SYN_SENT) {
            logMessage("rcv", Utils.duration(System.nanoTime(), this.startTime), ackSegment.getType(), ackSegment.getSeqNo(), 0);
            ackLock.lock();
            try {
                this.ack = packet;
                ackReceivedCondition.signal();
            } finally {
                ackLock.unlock();
            }
        }
        // for sending data and receive data
        if (this.state == State.ESTABLISHED || this.state == State.CLOSING) {
            logMessage("rcv", Utils.duration(System.nanoTime(), this.startTime), ackSegment.getType(), ackSegment.getSeqNo(), 0);
            removeBeforeAndIncluding(ackSegment.getSeqNo());
        }
        if (this.state == State.FIN_WAIT) {
            logMessage("rcv", Utils.duration(System.nanoTime(), this.startTime), ackSegment.getType(), ackSegment.getSeqNo(), 0);
            ackLock.lock();
            try {
                this.ack = packet;
                ackReceivedCondition.signal();
            } finally {
                ackLock.unlock();
            }
        }
    }

    public void run() throws IOException {
        ptpOpen();
        if (this.state == State.ESTABLISHED) {
            ptpSend();
            ptpClose();
        }
        if (this.state == State.CLOSED) {
            writeLogToFile();
        }
    }

    private void writeLogToFile() {
        this.log.append(String.format("Amount of (original) Data Transferred (in bytes): %d\n" +
                        "Number of Data Segments Sent (excluding retransmissions): %d\n" +
                        "Number of Retransmitted Data Segments: %d\n" +
                        "Number of Duplicate Acknowledgements received: %d%n",
                this.bytesSentCounter, this.segmentSentCounter,
                this.retransmittedDataSegmentsCounter, this.duplicatedACKCounter));
        Utils.writeLogToFile(this.log, "Sender_log.txt");
    }

    public static void main(String[] args) {
//                59606 56007 test1.txt 1000 rto
        args = new String[]{"7777", "8888", "FileToSend.txt", "3000", "200"};
        Logger.getLogger(Sender.class.getName()).setLevel(Level.ALL);
        if (args.length != 5) {
            System.err.println("\n===== Error usage, java Sender senderPort receiverPort FileReceived.txt maxWin rto ======\n");
            System.exit(0);
        }
        try {
            Sender sender = new Sender(Integer.parseInt(args[0]), Integer.parseInt(args[1]), args[2], Integer.parseInt(args[3]), Integer.parseInt(args[4]));
            sender.run();
        } catch (IOException ignored) {
        }
    }
}

