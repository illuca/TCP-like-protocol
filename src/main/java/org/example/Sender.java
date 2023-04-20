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

    private StringBuffer log = null;
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
    private int retransmittedSegmentCounter = 0;
    private int duplicatedACKCounter = 0;
    private final Lock lock = new ReentrantLock();
    private Thread receiveThread;
    private Thread sendFileThread;
    Condition condition = lock.newCondition();

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
        this.receiveThread = new Thread(this::listen);
        this.sendFileThread = new Thread(this::sendFile);

        // init the UDP socket
        Logger.getLogger(Sender.class.getName()).log(Level.INFO, "The sender is using the address {0}:{1}", new Object[]{senderAddress, senderPort});
        this.senderSocket = new DatagramSocket(senderPort, senderAddress);
//        this.senderSocket.setSoTimeout(this.rto);
        // start the listening sub-thread

        // todo add codes here
    }

    // pointer to pointer
    public void ptpOpen()  {
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        Future<STPSegment> future = executorService.submit(new SubThread(senderSocket));

        int counter = 0;
        while ((this.state == State.CLOSED || this.state == State.SYN_SENT) && counter < 3) {
            Random random = new Random();
            this.ISN = random.nextInt(65536);
            sendFlagPacket(STPSegment.SYN, this.ISN);
            setState(State.SYN_SENT);
            try {
                STPSegment ackSegment = future.get(this.rto, TimeUnit.MILLISECONDS);
                System.out.println("ackSegment = " + ackSegment.toString());
                int type = ackSegment.getType();
                int ackNo = ackSegment.getSeqNo();
                if (type == STPSegment.ACK && ackNo == Utils.seq(this.ISN + 1)) {
                    // receive action
                    setState(State.ESTABLISHED);
                    System.out.println("Handshake succeed.");
                } else {
                    System.out.println("Handshake failed.");
                }
            } catch (TimeoutException e) {
                counter++;
                this.retransmittedSegmentCounter++;
            } catch (ExecutionException | InterruptedException ignored) {
            }
        }
        executorService.shutdown();
        if (this.state != State.ESTABLISHED && counter == 3) {
            this.resetConnection();
        }
    }

    class SubThread implements Callable<STPSegment> {
        private final DatagramSocket senderSocket;

        public SubThread(DatagramSocket senderSocket) {
            this.senderSocket = senderSocket;
        }

        @Override
        public STPSegment call() {
            DatagramPacket ack = receive();
            STPSegment segment = STPSegment.fromBytes(ack.getData(), ack.getLength());
            return segment;
        }
    }

    public void sendFile() {
        while (this.state == State.ESTABLISHED) {
            for (STPSegment stp : this.unAcked) {
                byte[] data = stp.toBytes();
                DatagramPacket packet = new DatagramPacket(data, data.length, this.receiverAddress, this.receiverPort);
                //                System.out.println("this.sent=" + this.sent);
                if (!this.sent.contains(stp.getSeqNo()) || isTimeout(stp)
                ) {
                    double sendTime = Utils.duration(System.nanoTime(), this.startTime);
                    stp.setSendTime(sendTime);
                    stp.setACKTime(sendTime + rto);

                    try {
                        this.senderSocket.send(packet);
                    } catch (IOException ignore) {
                    }
                    try {
                        lock.lock();
                        logMessage("snd", stp.getSendTime(), STPSegment.DATA, stp.getSeqNo(), stp.getPayloadLength());
                        this.sent.add(stp.getSeqNo());
                    } finally {
                        lock.unlock();
                    }
                }
            }
        }
    }

    public void ptpSend() throws IOException {
        receiveThread.start();
        sendFileThread.start();

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
//                    System.out.println("seq=" + seq);
                STPSegment newStp = new STPSegment(STPSegment.DATA, seq, expectedACK, Arrays.copyOfRange(buffer, 0, bytesRead), sentTime, expectedTime);
//                    System.out.println("newStp = " + newStp);
                this.unAcked.offer(newStp);
                seq = expectedACK;
            }
        }
        fis.close();
    }

    private boolean isTimeout(STPSegment stp) {
        double duration = Utils.duration(System.nanoTime(), this.startTime);
//        System.out.println(duration + "expectedTime:" + stp.getAckTime());
        if (duration > stp.getAckTime()) {
            return true;
        }
        return false;
    }

    public void ptpClose() {
        setState(State.CLOSING);

        System.out.println("Sender.ptpClose");
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        Future<STPSegment> future = executorService.submit(new SubThread(senderSocket));

        int counter = 0;
        while ((this.state == State.CLOSING || this.state == State.FIN_WAIT) && counter < 3) {
            sendFlagPacket(STPSegment.FIN, this.lastDataSequence);
            setState(State.FIN_WAIT);
            try {
                STPSegment incomingSegment = future.get(rto, TimeUnit.MILLISECONDS);
                int type = incomingSegment.getType();
                int seqNo = incomingSegment.getSeqNo();
                if (type == STPSegment.ACK && seqNo == Utils.seq(this.ISN + 1)) {
                    setState(State.CLOSED);
                    System.out.println("Closed succeed.");
                } else {
                    System.out.println("Closed failed.");
                }
            } catch (TimeoutException e) {
                counter++;
                this.retransmittedSegmentCounter++;
            } catch (ExecutionException | InterruptedException ignored) {
            }
        }
        executorService.shutdown();
        if (this.state == State.FIN_WAIT && counter == 3) {
            this.resetConnection();
        }

        if (this.state == State.CLOSED) {
            try {
                lock.lock();
                System.out.println("Sender.ptpClose");
                this.senderSocket.close();
                System.out.println("Sender.ptpClose1");
            } finally {
                lock.unlock();
            }
        }
    }

    private void resetConnection() {
        sendFlagPacket(STPSegment.RESET, 0);
        setState(State.CLOSED);
    }

    public void setState(State state) {
        try {
            lock.lock();
            this.state = state;
        } finally {
            lock.unlock();
        }
    }

    public void sendFlagPacket(int type, int sequenceNumber)  {
        double sentTime = Utils.duration(System.nanoTime(), this.startTime);
        STPSegment stp = new STPSegment(type, sequenceNumber, null);
        byte[] data = stp.toBytes();
        DatagramPacket packet = new DatagramPacket(data, data.length, this.receiverAddress, this.receiverPort);
        try {
            senderSocket.send(packet);
        } catch (IOException ignore) {
        }
        try {
            lock.lock();
            this.logMessage("snd", sentTime, type, sequenceNumber, 0);
        } finally {
            lock.unlock();
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

    private DatagramPacket receive() {
        byte[] buffer = new byte[4];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length, this.receiverAddress, this.receiverPort);
        try {
            this.senderSocket.receive(packet);
        } catch (IOException ignore) {
        }
        STPSegment incomingSegment = STPSegment.fromBytes(packet.getData());
        try {
            lock.lock();
            logMessage("rcv", Utils.duration(System.nanoTime(), this.startTime), incomingSegment.getType(), incomingSegment.getSeqNo(), 0);
            return packet;
        } finally {
            lock.unlock();
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

    public void listen() {
        while (this.state == State.ESTABLISHED) {
            byte[] buffer = new byte[4];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, receiverAddress, receiverPort);
            try {
                this.senderSocket.receive(packet);
            } catch (IOException ignore) {
                System.out.println("exception");
            }
            STPSegment ackSegment = STPSegment.fromBytes(packet.getData());
            try {
                lock.lock();
                logMessage("rcv", Utils.duration(System.nanoTime(), this.startTime), ackSegment.getType(), ackSegment.getSeqNo(), packet.getLength() - 4);
            } finally {
                lock.unlock();
            }
            removeBeforeAndIncluding(ackSegment.getSeqNo());
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
        this.log.append(String.format("%d\t%d\t%d\t%d%n",
                this.bytesSentCounter, this.segmentSentCounter,
                this.retransmittedSegmentCounter, this.duplicatedACKCounter));

        File file = new File("sender_log.txt");
        try (FileWriter fileWriter = new FileWriter(file);
             BufferedWriter bufferedWriter = new BufferedWriter(fileWriter)) {
            bufferedWriter.write(this.log.toString());
        } catch (IOException e) {
            System.err.println("An error occurred while writing the StringBuffer to the file: " + e.getMessage());
            e.printStackTrace();
        }
        System.out.println("writelogtofile end");
    }

    public static void main(String[] args) {
//                59606 56007 test1.txt 1000 rto
        args = new String[]{"7777", "8888", "FileToSend.txt", "1000", "20"};
        Logger.getLogger(Sender.class.getName()).setLevel(Level.ALL);
        if (args.length != 5) {
            System.err.println("\n===== Error usage, java Sender senderPort receiverPort FileReceived.txt maxWin rto ======\n");
            System.exit(0);
        }

        try {
            Sender sender = new Sender(Integer.parseInt(args[0]), Integer.parseInt(args[1]), args[2], Integer.parseInt(args[3]), Integer.parseInt(args[4]));
            sender.run();
            sender.sendFileThread.stop();
            sender.receiveThread.stop();
            sender.senderSocket.close();
            System.out.println(sender.state);
            System.out.println("end");
        } catch (IOException ignored) {
        }
    }
}

