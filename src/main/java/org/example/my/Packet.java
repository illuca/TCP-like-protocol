package org.example.my;

import java.io.Serializable;

public class Packet implements Serializable {
    static final long serialVersionUID = 1L; //assign a long value
    // The "type" field takes on 5 possible values. DATA = 0, ACK = 1, SYN = 2, FIN = 3, RESET = 4.
    // Unlike TCP, in which multiple types can be set simultaneously, STP segments must be of exactly
    // The Maximum Segment Size (MSS) (excluding headers) for a STP segment is 1000 bytes.
    // A DATA segment can thus be up to 1004 bytes long.
    // The last DATA segment for the file being transferred may contain less than 1000 bytes
    // as the file size may not be a multiple of 1000 bytes.
    // // All segments excluding DATA segments should only contain the headers and must thus be 4 bytes long.
//    private short type;
    // The "seqNo" field indicates the sequence number of the segment.
    // This field is used in all segments except RESET segment when it is set to zero.
    // the “seqNo” field contains the ack number for the ACK segments.
//    private short seqNo;
    private byte[] payload;

    public Packet(short type, short seqNo, byte[] payload) {
//        this.type = type;
//        this.seqNo = seqNo;
        this.payload = payload;
    }


//    public short getType() {
//        return type;
//    }

//    public void setType(short type) {
//        this.type = type;
//    }

//    public short getSeqNo() {
//        return seqNo;
//    }

//    public void setSeqNo(short seqNo) {
//        this.seqNo = seqNo;
//    }

    public byte[] getPayload() {
        return payload;
    }

    public void setPayload(byte[] payload) {
        this.payload = payload;
    }
}