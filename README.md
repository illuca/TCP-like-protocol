# Overview

- Implemented a UDP-based TCP-like protocol in Java using multithreading for reliable file transmission
- Simulated packet loss on the client and server sides with flp and rlp probabilities
- Supported three-way and four-way handshakes, timeout retransmissions, fast retransmissions, sliding windows, and Go-Back-N mechanisms

# How to run

Language and platform: Java 11.0.17

Project Structure:

```bash
$PROJECT
├── Makefile
├── Receiver.java
├── Sender.java
├── Utils.java
└── STPSegment.java
└── README.md
```

First, start receiver:

```bash
cd $PROJECT
make run-receiver ARGS="<receiverPort> <senderPort> <fileToReceive> <forwardLossProbability> <reverseLossProbability>"
eg:
make run-receiver ARGS="8888 7777 FileReceived.txt 0.5 0.5"
```

- `receiverPort`: The UDP port number for the Receiver.
- `senderPort`: The UDP port number from which the Receiver expects to receive data.
- `fileToReceive`: Path where the received file will be saved.
- `forwardLossProbability`: The probability that any outgoing segment (ACK) is lost.
- `reverseLossProbability`: The probability that any incoming segment (Data) is lost.

Second, start sender:

```bash
cd $PROJECT
make run-sender ARGS="<senderPort> <receiverPort> <fileToSend> <maxWindowSize> <retransmissionTimeout>"
eg:
make run-sender ARGS="7777 8888 FileToSend.txt 1000 20"
```

- `senderPort`: The UDP port number for the Sender.
- `receiverPort`: The UDP port number on which the Receiver is expecting to receive data.
- `fileToSend`: Path to the file that needs to be sent.
- `maxWindowSize`: The maximum window size in bytes for the sender's window.
- `retransmissionTimeout`: Timeout in milliseconds for retransmissions.

After doing these two steps, the transmission will start.

After the transmission is complete, a file called `FileReceived.txt` will be created in the same directory as the receiver.

Besides, two log files `Receiver_log.txt` and `Sender_log.txt` will be created in the same directory as the sender and receiver.

# Main design and description

## Sender

**Sliding window**:

Use a `queue` with max_win size to store all segment read from file. If the segment is acknowledged by receiver, then remove current segment and all the segments before it from the queue. As a result, queue has more space to store segments read from file, looking like it is sliding.

**Timeout retransmit**:

For each segment read from file, add sending time and expected time to receive its ACK. Use a thread to keep checking all the segments in `queue` and if a segment is found that current time is greater than its expected time to receive ACK, then update sending time and expected ACK time and retransmit it.

**Fast retransmit:**

Set a boolean `interrupt`. There is a thread for listening segments from receiver. Every time it receives an ACK, it will try to find remove segments with cumulative expected ACK from queue.

Let say sender sends pkt1, pkt2, pkt3, pkt4, pkt5. Receiver receives pkt1 and sends ack2. Then pkt1 will be removed from `queue`.

Pkt2 gets lost due to flp. Receiver receives pkt3, pkt4, pkt5. Thus sender has 3 duplicate ack2, then `queue` fails to remove pkt1 for 3 times because pkt1 has been removed. Next, the `interrupt` i set true, which will break current sending process and re-traverse the `queue`.

## Receiver

Store in correct order:

The `expectedSeq` is initialised as one plus sequence number of SYN after connection established.

Every time receiver receives a segment, it checks if the `expectedSeq` equals sequence number of the segment. If not equal, then the segment will be stored in a `hash table`. Otherwise, the segment is stored in an `array`. `expectedSeq` is updated as segment sequence number plus segment length and use updated `expectedSeq` to keep finding the next segment  in the `hash table` and store in `array`. util it cannot find the next. Segments stored in the `array` are in order.

# Trade-off

I considered that sender used serialise a segment to bytes and sent bytes through udp and then receiver deserialised it to a segment. However, deserialisation always got error. I collected information and it is because the length problem. UDP always have a buffer with 1024 Bytes and the empty part will be filled with 0. Thus during deserialisation, the real length of objects cannot be obtained. Afterwards, i solved the problem but considering the extra length of serialisation, i gave up this idea.

Before, in sender i use three seperate threads to listen ACK from receiver in different period, including SYN_SENT, ESTABLISHED, FIN_WAIT. But it is hard to manage 3 threads. Finally, i move all functions in one thread and use state judgement to let them deal with ACK from different periods.

# Reference

https://www.cs.swarthmore.edu/~newhall/unixhelp/javamakefiles.html

https://docs.oracle.com/en/java/javase/11/docs/api/

https://google.github.io/styleguide/javaguide.html





