package org.example;

import java.nio.ByteBuffer;
import java.util.Arrays;

class STPSegment {
    public static final int DATA = 0;
    public static final int ACK = 1;
    public static final int SYN = 2;
    public static final int FIN = 3;
    public static final int RESET = 4;

    private int type;
    private int seqNo;
    private int expectedACK;
    private long sendTime;
    private long expectedACKTime;
    private byte[] payload;

    public STPSegment(int type, int seqNo, byte[] payload) {
        this.type = type;
        this.seqNo = seqNo & 0xFFFF; // Ensure the value is in the range 0-65535
        this.payload = payload;
    }
    public STPSegment(int type, int seqNo, int expectedACK, byte[] payload, long sendTime, long expectedACKTime) {
        this.type = type;
        this.seqNo = seqNo & 0xFFFF;
        this.expectedACK = expectedACK;
        this.payload = payload;
        this.sendTime = sendTime;
        this.expectedACKTime = expectedACKTime;
    }

    public byte[] toBytes() {
        int length = 0;
        if (payload != null && payload.length > 0) {
            length = payload.length;
        }
        ByteBuffer buffer = ByteBuffer.allocate(2 * 2 + length);
        buffer.putShort((short) type);
        buffer.putShort((short) (seqNo & 0xFFFF));
        if (payload != null && payload.length > 0) {
            buffer.put(payload);
        }
        return buffer.array();
    }

    public static STPSegment fromBytes(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        int type = buffer.getShort();
        int seqno = buffer.getShort() & 0xFFFF; // Convert the signed short to an unsigned integer
        byte[] payload = new byte[buffer.remaining()];
        buffer.get(payload);
        return new STPSegment(type, seqno, payload);
    }

    public int getExpectedACK() {
        return expectedACK;
    }

    public void setExpectedACK(int expectedACK) {
        this.expectedACK = expectedACK;
    }

    public long getSendTime() {
        return sendTime;
    }

    public void setSendTime(long sendTime) {
        this.sendTime = sendTime;
    }

    public long getExpectedACKTime() {
        return expectedACKTime;
    }

    public void setExpectedACKTime(long expectedACKTime) {
        this.expectedACKTime = expectedACKTime;
    }

    public static STPSegment fromBytes(byte[] bytes, int totalLength) {
        byte[] realData = Arrays.copyOfRange(bytes, 0, totalLength);
        ByteBuffer buffer = ByteBuffer.wrap(realData);
        int type = buffer.getShort();
        int seqno = buffer.getShort() & 0xFFFF; // Convert the signed short to an unsigned integer
        byte[] payload = new byte[buffer.remaining()];
        buffer.get(payload);
        return new STPSegment(type, seqno, payload);
    }

    @Override
    public String toString() {
        return "STPSegment{" +
                "type=" + type +
                ", seqNo=" + seqNo +
                ", expectedACK=" + expectedACK +
                ", sendTime=" + sendTime +
                ", expectedACKTime=" + expectedACKTime +
                ", payload=" + Arrays.toString(payload) +
                '}';
    }

    // Getters and setters for type, seqno, and payload
    // ...

    public int getType() {
        return type;
    }

    public void setType(int type) {
        this.type = type;
    }

    public int getSeqNo() {
        return seqNo;
    }

    public void setSeqNo(int seqNo) {
        this.seqNo = seqNo;
    }

    public byte[] getPayload() {
        return payload;
    }

    public void setPayload(byte[] payload) {
        this.payload = payload;
    }
}