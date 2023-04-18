package org.example.test2;

import java.net.DatagramPacket;
import java.net.InetAddress;

public class STPPacket {
    // The "type" field takes on 5 possible values. DATA = 0, ACK = 1, SYN = 2, FIN = 3, RESET = 4.
    // Unlike TCP, in which multiple types can be set simultaneously, STP segments must be of exactly
    // The Maximum Segment Size (MSS) (excluding headers) for a STP segment is 1000 bytes.
    // A DATA segment can thus be up to 1004 bytes long.
    // The last DATA segment for the file being transferred may contain less than 1000 bytes
    // as the file size may not be a multiple of 1000 bytes.
    // // All segments excluding DATA segments should only contain the headers and must thus be 4 bytes long.
    short type;
    // The "seqno" field indicates the sequence number of the segment.
    // This field is used in all segments except RESET segment when it is set to zero.
    // the “seqno” field contains the ack number for the ACK segments.
    short seqNo;

    DatagramPacket datagramPacket;


    public STPPacket() {
    }
    public STPPacket(byte[] fileBuffer, int bytesRead, short type, short seqNo, InetAddress receiverHost, int receiverPort) {
//        fileBuffer[bytesRead] = (byte) (type>>8);
//        fileBuffer[bytesRead+1] = (byte) (type);
//        fileBuffer[bytesRead+2] = (byte) (seqNo >> 8);
//        fileBuffer[bytesRead + 3] = (byte) (seqNo);
        this.datagramPacket = new DatagramPacket(fileBuffer, bytesRead, receiverHost, receiverPort);
    }

    public void setDatagramPacket(DatagramPacket datagramPacket) {
        this.datagramPacket = datagramPacket;
    }

    public DatagramPacket getDatagramPacket() {
        return datagramPacket;
    }

}
