package org.example;


import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;


/**
 * The Sender will be able to connect the Receiver via UDP
 */
public class Sender {
    public static boolean allowLog = false;

    enum State {
        CLOSED,
        SYN_SENT,
        ESTABLISHED,
        CLOSING,
        FIN_WAIT,
    }

    // the UDP port number to be used by the sender to send PTP segments to the receiver
    private final int senderPort;
    // the UDP port number on which receiver is expecting to receive PTP segments from the sender
    private final int receiverPort;
    private final InetAddress senderAddress;
    private int ISN;
    private int lastDataSequence;
    private final InetAddress receiverAddress;
    private final DatagramSocket senderSocket;
    private final Lock socketLock = new ReentrantLock();
    private final StringBuffer log;
    private volatile State state = State.CLOSED;
    private final Lock stateLock = new ReentrantLock();
    private final Condition stateCondition = stateLock.newCondition();

    // the name of the text file that must be transferred from sender to receiver using your reliable transport protocol.
    private final String filename;
    // the maximum window size in bytes for the sender window.
    // must greater or equal to 1000 and should be n * 1000
    private final int maxWin;
    // the value of the retransmission timer in milliseconds. This should be an unsigned integer.
    private final int rto;
    private final BlockingQueue<STPSegment> unAcked;
    private Set<Integer> sent;
    private AtomicBoolean duplicateACKFlag = new AtomicBoolean(false);
    private AtomicBoolean interrupt = new AtomicBoolean(false);
    private Map<Integer, Integer> duplicateACKs = new ConcurrentHashMap<>();

    private long startTime = 0;

    private int bytesSentCounter = 0;
    private int segmentSentCounter = 0;
    private int retransmittedDataSegmentsCounter = 0;
    private int duplicatedACKCounter = 0;
    private DatagramPacket ack;
    private final Lock ackLock = new ReentrantLock();
    private final Condition ackReceivedCondition = ackLock.newCondition();
    private Thread receiveThread;
    private Thread sendFileThread;
    private Random random = new Random();

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
        this.sent = new ConcurrentSkipListSet<>();
        this.senderSocket = new DatagramSocket(senderPort, senderAddress);

        this.receiveThread = new Thread(() -> {
            // wait until the state is SYN_SENT
            while (this.state != State.SYN_SENT) {
                try {
                    stateLock.lock();
                    stateCondition.await();
                } catch (InterruptedException ignore) {
                } finally {
                    stateLock.unlock();
                }
            }
            // keep receiving ACK until the state is CLOSED
            while (this.state != State.CLOSED) {
                this.receiveACK();
            }
        });
        this.sendFileThread = new Thread(() -> {
            // wait until the state is ESTABLISHED
            while (this.state != State.ESTABLISHED) {
                try {
                    stateLock.lock();
                    stateCondition.await();
                } catch (InterruptedException ignore) {
                } finally {
                    stateLock.unlock();
                }
            }
            // keep sending data until the state is CLOSED
            while (this.state == State.ESTABLISHED || this.state == State.CLOSING) {
                sendFile();
            }
        });
    }

    // point to point
    public void ptpOpen() {
        setState(State.SYN_SENT);
        this.receiveThread.start();

        int counter = 0;
        while (this.state == State.SYN_SENT && counter < 3) {
            try {
                ackLock.lock();
//                this.ISN = this.random.nextInt(65536);
                this.ISN = 999;
                sendFlagPacket(STPSegment.SYN, this.ISN);
                /**
                 * true: If the thread exited waiting because it was signalled (typically via signal()
                 *       or signalAll() methods on the Condition object).
                 * false: If the thread exited waiting because the specified waiting time elapsed.
                 */
                boolean ackReceived = ackReceivedCondition.await(this.rto, TimeUnit.MILLISECONDS);
                if (ackReceived) {
                    STPSegment ackSegment = STPSegment.fromBytes(this.ack.getData(), this.ack.getLength());
                    if (ackSegment.getType() == STPSegment.ACK && ackSegment.getSeqNo() == Utils.seq(this.ISN + 1)) {
                        setState(State.ESTABLISHED);
                        break;
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
        if (!new File(this.filename).exists()) {
            System.out.println(this.filename + " does not exist.");
            return;
        }
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
            if (!this.sent.contains(stp.getSeqNo()) || Utils.isTimeout(stp, this.startTime) || this.duplicateACKFlag.get()) {
                double sendTime = Utils.duration(System.nanoTime(), this.startTime);
                stp.setSendTime(sendTime);
                stp.setACKTime(sendTime + rto);
                if (this.sent.contains(stp.getSeqNo())) {
                    this.retransmittedDataSegmentsCounter++;
                }
                try {
                    this.senderSocket.send(packet);
                    this.sent.add(stp.getSeqNo());
                    logMessage("snd", stp.getSendTime(), STPSegment.DATA, stp.getSeqNo(), stp.getPayloadLength());
                    if (this.interrupt.get()) {
                        this.interrupt.set(false);
                        break;
                    }
                } catch (IOException ignore) {
                }
            }
        }
    }

    public void ptpClose() {
        // When the sending application (sitting above STP) is finished generating data,
        // it issues a "close" operation to STP. This causes the sender to enter the CLOSING state.
        // At this point, the sender must still ensure that any buffered data arrives at the receiver reliably.
        setState(State.CLOSING);
        while (this.unAcked.size() != 0) {
        }
        sendFlagPacket(STPSegment.FIN, this.lastDataSequence);
        setState(State.FIN_WAIT);
        int counter = 0;
        while (this.state == State.FIN_WAIT && counter < 2) {
            try {
                ackLock.lock();
                boolean ackReceived = ackReceivedCondition.await(this.rto, TimeUnit.MILLISECONDS);
                if (ackReceived) {
                    STPSegment segment = STPSegment.fromBytes(this.ack.getData(), this.ack.getLength());
                    if (segment.getType() == STPSegment.ACK && segment.getSeqNo() == Utils.seq(this.lastDataSequence + 1)) {
                        setState(State.CLOSED);
                        break;
                    } else {
                    }
                } else {
                    sendFlagPacket(STPSegment.FIN, this.lastDataSequence);
                    counter++;
                }
            } catch (InterruptedException e) {
            } finally {
                ackLock.unlock();
            }
        }
        if (this.state == State.FIN_WAIT && counter == 2) {
            sendFlagPacket(STPSegment.RESET, 0);
            this.doCloseConnection();
        }
        if (this.state == State.CLOSED) {
            doCloseConnection();
        }
    }


    private void doCloseConnection() {
        try {
            if (this.receiveThread.isAlive()) {
                this.receiveThread.stop();
            }
            if (this.sendFileThread.isAlive()) {
                this.receiveThread.stop();
            }
            socketLock.lock();
            this.senderSocket.close();
        } finally {
            socketLock.unlock();
        }
        setState(State.CLOSED);
    }

    public void setState(State state) {
        if (this.state == state) {
            return;
        }
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
        print(newLog);
        this.log.append(newLog);
    }

    private void print(Object newLog) {
        if (allowLog) {
            System.out.print(newLog);
        }
    }
    private void println(Object newLog) {
        if (allowLog) {
            System.out.println(newLog);
        }
    }

    public int removeBeforeAndIncluding(int x) {
        int foundSeqNo = -1;
        Set<STPSegment> set = new HashSet<>();

        for (STPSegment segment : this.unAcked) {
            set.add(segment);
            if (segment.getExpectedACK() == x) {
                foundSeqNo = segment.getSeqNo();
                break;
            }
        }
        if (foundSeqNo != -1) {
            this.unAcked.removeAll(set);
            this.segmentSentCounter += set.size();
            this.bytesSentCounter += set.stream().mapToInt(STPSegment::getPayloadLength).sum();
        }
        return foundSeqNo;
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
            if (this.duplicateACKs.get(ackSegment.getSeqNo()) == null) {
                this.duplicateACKs.put(ackSegment.getSeqNo(), 1);
            } else {
                this.duplicateACKs.merge(ackSegment.getSeqNo(), 1, Integer::sum);
                this.duplicatedACKCounter++;
            }
            println(this.duplicateACKs);
            int removedSeq = removeBeforeAndIncluding(ackSegment.getSeqNo());
            if (this.duplicateACKs.getOrDefault(ackSegment.getSeqNo(), 0) > 3) {
                if (removedSeq == -1) {
                    println(">3: " + ackSegment.getSeqNo());
                    this.duplicateACKFlag.set(true);
                    this.interrupt.set(true);
                } else {
                    // remove successfully
                    this.duplicateACKFlag.set(false);
                    this.interrupt.set(true);
                }
            }
        }
        if (this.state == State.FIN_WAIT) {
            logMessage("rcv", Utils.duration(System.nanoTime(), this.startTime), ackSegment.getType(), ackSegment.getSeqNo(), 0);
            this.ackLock.lock();
            try {
                this.ack = packet;
                ackReceivedCondition.signal();
            } finally {
                this.ackLock.unlock();
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
        this.duplicatedACKCounter = this.duplicateACKs.values().stream()
                .filter(value -> value >= 1)
                .mapToInt(Integer::intValue)
                .sum();

        this.log.append(String.format("Amount of (original) Data Transferred (in bytes): %d\n" +
                        "Number of Data Segments Sent (excluding retransmissions): %d\n" +
                        "Number of Retransmitted Data Segments: %d\n" +
                        "Number of Duplicate Acknowledgements received: %d%n",
                this.bytesSentCounter, this.segmentSentCounter,
                this.retransmittedDataSegmentsCounter, this.duplicatedACKCounter));
        Utils.writeLogToFile(this.log, "Sender_log.txt");
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            // sender port, receiver port, file name, max window size, rto
            args = new String[]{"7777", "8888", "FileToSend.txt", "7000", "100"};
            System.out.println("Args are empty. Use default configuration.");
            Sender.allowLog = true;
        }
        if (args.length != 5) {
            System.err.println("\n===== Error usage, java Sender senderPort receiverPort FileReceived.txt maxWin rto ======\n");
            System.exit(0);
        }
        try {
            Sender sender = new Sender(Integer.parseInt(args[0]), Integer.parseInt(args[1]), args[2], Integer.parseInt(args[3]), Integer.parseInt(args[4]));
            sender.run();
            if (sender.receiveThread.isAlive() || sender.sendFileThread.isAlive()) {
                sender.doCloseConnection();
            }
        } catch (IOException ignored) {
        }
    }
}

