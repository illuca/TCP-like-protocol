# Sender and Receiver Application

## Overview
This project implements a UDP-based file transfer application with a custom protocol to ensure reliable data transmission over an unreliable network. The application is divided into two main components: the Sender and the Receiver. The Sender is responsible for sending a file, handling acknowledgments, and managing timeouts and packet losses. The Receiver listens for incoming data, handles packet ordering, and sends acknowledgments back to the Sender.

## Features
- **Reliable Transmission**: Implements a form of the Selective Repeat protocol to ensure reliable data transmission.
- **Packet Loss Simulation**: Simulates forward and reverse packet loss using configurable loss probabilities (FLP and RLP).
- **Concurrency**: Uses multiple threads to handle sending and receiving data simultaneously.
- **State Management**: Manages connection states (e.g., SYN_SENT, ESTABLISHED, CLOSING) to handle different phases of the communication protocol.
- **Timeouts and Retransmissions**: Implements a timeout mechanism to handle lost packets and retransmits data when necessary.

## Prerequisites
- Java 8 or higher
- Network access between the sender and receiver machines (if not run locally)

## Usage
### Configuration

### Running the Sender
If you didn't provide the arguments, the default values will be used.
```
java Sender <senderPort> <receiverPort> <fileToSend> <maxWindowSize> <retransmissionTimeout>
```

- `senderPort`: The UDP port number for the Sender.
- `receiverPort`: The UDP port number on which the Receiver is expecting to receive data.
- `fileToSend`: Path to the file that needs to be sent.
- `maxWindowSize`: The maximum window size in bytes for the sender's window.
- `retransmissionTimeout`: Timeout in milliseconds for retransmissions.

### Running the Receiver
If you didn't provide the arguments, the default values will be used.
```
java Receiver <receiverPort> <senderPort> <fileToReceive> <forwardLossProbability> <reverseLossProbability>
```
- `receiverPort`: The UDP port number for the Receiver.
- `senderPort`: The UDP port number from which the Receiver expects to receive data.
- `fileToReceive`: Path where the received file will be saved.
- `forwardLossProbability`: The probability that any outgoing segment (ACK) is lost.
- `reverseLossProbability`: The probability that any incoming segment (Data) is lost.